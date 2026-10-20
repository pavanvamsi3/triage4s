package triage4s.demo

import triage4s.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{Executors, ExecutorService}
import scala.util.Try
import upickle.default.*

final case class RunItem(
    issue: Issue,
    status: String,
    response: Option[TriageResponse] = None,
    error: Option[String] = None,
    /** Provider calls the issue took, retries included, when that is known and more than one. */
    attempts: Option[Long] = None
):
  def json: ujson.Value =
    val value = ujson.Obj("issue" -> writeJs(issue), "status" -> status)
    response.foreach(r => value("response") = writeJs(r))
    error.foreach(e => value("error") = e)
    attempts.foreach(n => value("attempts") = ujson.Num(n.toDouble))
    value

final case class Run(id: String, mode: String, status: String, items: Vector[RunItem], notice: String):
  def json: ujson.Value = ujson.Obj(
    "id" -> id,
    "mode" -> mode,
    "status" -> status,
    "items" -> ujson.Arr.from(items.map(_.json)),
    "notice" -> notice
  )

final class RunService(
    loadLive: (String, Int) => Either[String, Vector[Issue]],
    store: SnapshotStore,
    classifier: () => Either[String, Classifier],
    val taxonomy: Taxonomy,
    worker: ExecutorService = Executors.newSingleThreadExecutor(),
    /** Provider calls made so far; the difference across one issue is how many attempts it took (#91). */
    attempts: () => Long = () => 0L
) extends AutoCloseable:
  private var sourceMode = "live"
  private var loaded = Vector.empty[Issue]
  private var replay: Option[Replay] = None
  private var current: Option[Run] = None
  // the id of the run asked to stop after its current issue (#14)
  private var stopRequested: Option[String] = None
  private var closed = false

  def currentRun: ujson.Value = synchronized {
    current.fold[ujson.Value](ujson.Obj("status" -> "idle", "items" -> ujson.Arr()))(_.json)
  }

  def load(mode: String, state: String, pages: Int): Either[String, ujson.Value] = synchronized {
    if closed then Left("The demo is shutting down.")
    else if current.exists(_.status == "running") then Left("Wait for the current run before loading issues.")
    else
      loaded = Vector.empty
      replay = None
      current = None
      val result = mode match
        case "live"     => loadLive(state, pages).map(issues => (issues, Option.empty[Replay]))
        case "snapshot" => store.loadIssues().map(issues => (issues, Option.empty[Replay]))
        case "replay"   => store.loadReplay(taxonomy).map(r => (r.items.map(_.issue), Some(r)))
        case _          => Left("Mode must be live, snapshot, or replay.")
      result.map { case (issues, recording) =>
        sourceMode = mode
        loaded = issues
        replay = recording
        current = None
        val notice = mode match
          case "live" =>
            s"Loaded ${issues.size} issues from bounded GitHub pages; pull requests excluded. Results may be capped."
          case "snapshot" =>
            "Saved input only. Running triage still sends selected title/body content to OpenAI."
          case _ => "Recorded replay: no GitHub or model requests. These are previously captured results."
        val value = ujson.Obj(
          "mode" -> mode,
          "issues" -> writeJs(issues),
          "notice" -> notice
        )
        recording.foreach(r => value("replayCapturedAt") = r.capturedAt)
        value
      }
  }

  def saveSnapshot(): Either[String, String] = synchronized {
    if loaded.isEmpty then Left("Load issues before saving a snapshot.")
    else if current.exists(_.status == "running") then Left("Wait for the current run before saving.")
    else
      store
        .saveIssues(loaded)
        .map(_ => "Saved local input snapshot. Review redistribution rights before sharing.")
  }

  def start(ids: Vector[String], consent: Boolean): Either[String, ujson.Value] = synchronized {
    if closed then Left("The demo is shutting down.")
    else if current.exists(_.status == "running") then
      Left("A run is already active. No overlapping runs are allowed.")
    else if ids.isEmpty || ids.size > 10 || ids.distinct.size != ids.size then
      Left("Select 1 to 10 distinct issues.")
    else if ids.exists(id => !loaded.exists(_.input.id == id)) then
      Left("Selection contains an issue not in the currently loaded source.")
    else if sourceMode != "replay" && !consent then
      Left("Review the selected public content and explicitly consent to sending it to OpenAI.")
    else
      val selected = ids.map(id => loaded.find(_.input.id == id).get)
      if sourceMode == "replay" then
        replay.toRight("No validated recording is loaded.").map { recording =>
          val responses = recording.items.map(item => item.issue.input.id -> item.response).toMap
          val items = selected.map { issue =>
            val response = responses(issue.input.id)
            RunItem(issue, statusFor(response), Some(response))
          }
          val run = Run(
            UUID.randomUUID().toString,
            "replay",
            "completed",
            items,
            s"Recorded replay captured ${recording.capturedAt}; no model calls."
          )
          current = Some(run)
          run.json
        }
      else
        classifier().flatMap { activeClassifier =>
          val startedRun = Run(
            UUID.randomUUID().toString,
            sourceMode,
            "running",
            selected.map(RunItem(_, "queued")),
            "Sequential classification. No automatic retries."
          )
          current = Some(startedRun)
          Try(
            worker.submit(
              new Runnable:
                override def run(): Unit = process(startedRun.id, selected, activeClassifier)
            )
          ).toEither.left
            .map { e =>
              current = Some(
                startedRun.copy(
                  status = "completed",
                  items = startedRun.items
                    .map(_.copy(status = "error", error = Some("The run worker could not start.")))
                )
              )
              s"Could not start the run worker (${e.getClass.getSimpleName})."
            }
            .map(_ => startedRun.json)
        }
  }

  /** Asks the run `id` to stop after its current issue (#14): the issue being classified finishes, and the
    * issues still queued are skipped, never sent. Idempotent; it answers the run as it stands. Only the
    * active run can be stopped: an older run's id is refused, so a stale page cannot stop a newer run. The
    * run stays `running` until its in-flight call returns, so no new run can start before then, and it ends
    * `stopped`, which cannot be recorded.
    */
  def stop(id: String): Either[String, ujson.Value] = synchronized {
    current match
      case Some(run) if run.id == id =>
        if run.status == "running" then
          stopRequested = Some(id)
          current =
            Some(run.copy(notice = "Stopping after the current issue. Queued issues will be skipped."))
        current.map(_.json).toRight("No run.")
      case Some(_) => Left("That run is no longer the active run.")
      case None    => Left("There is no run to stop.")
  }

  private def stopping(id: String): Boolean = synchronized(stopRequested.contains(id))

  private def process(id: String, issues: Vector[Issue], activeClassifier: Classifier): Unit =
    val stoppedAt = issues.indices.find { index =>
      if stopping(id) then true
      else
        classifyOne(id, issues(index), index, activeClassifier)
        false
    }
    synchronized {
      current = current.map { r =>
        if r.id != id then r
        else
          stoppedAt match
            case Some(from) =>
              r.copy(
                status = "stopped",
                items = r.items.zipWithIndex.map((item, i) =>
                  if i >= from then
                    item.copy(status = "skipped", error = Some("Skipped: the run was stopped."))
                  else item
                ),
                notice =
                  s"Stopped by request after ${from} of ${r.items.size} issues; the rest were not sent."
              )
            case None => r.copy(status = "completed")
      }
      stopRequested = None
    }

  private def classifyOne(id: String, issue: Issue, index: Int, activeClassifier: Classifier): Unit =
    update(id, index, RunItem(issue, "running"))
    val before = attempts()
    val result = Try(activeClassifier.triage(issue.input, taxonomy)).toEither.left
      .map(e => TriageError.ProviderFailure(s"Classifier failed (${e.getClass.getSimpleName})."))
      .flatMap(identity)
    val took = Some(attempts() - before).filter(_ > 1)
    val item = result match
      case Right(response) => RunItem(issue, statusFor(response), Some(response), attempts = took)
      case Left(error)     => RunItem(issue, "error", error = Some(error.message), attempts = took)
    update(id, index, item)

  private def statusFor(response: TriageResponse): String =
    if response.result.needsReview then "needs-review" else "suggested"

  private def update(id: String, index: Int, item: RunItem): Unit = synchronized {
    current = current.map(r => if r.id == id then r.copy(items = r.items.updated(index, item)) else r)
  }

  def record(): Either[String, String] = synchronized {
    current.toRight("Complete a live or snapshot triage run before recording.").flatMap { run =>
      if run.status == "stopped" then
        Left(
          "A stopped run cannot be recorded: some issues were skipped. Run the remaining issues explicitly."
        )
      else if run.mode == "replay" || run.status != "completed" then
        Left("Only completed live or snapshot runs can be recorded.")
      else if run.items.exists(_.response.isEmpty) then
        Left("Every selected issue must have a successful result before recording. Retry errors explicitly.")
      else
        val recording = Replay(
          1,
          Instant.now().toString,
          taxonomy,
          Triage.PromptVersion,
          run.items.map(i => RecordedIssue(i.issue, i.response.get))
        )
        store.saveReplay(recording).map(_ => "Saved genuine results locally for offline replay.")
    }
  }

  override def close(): Unit = synchronized {
    closed = true
    worker.shutdown()
  }
