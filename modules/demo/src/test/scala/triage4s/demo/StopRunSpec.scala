package triage4s.demo

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import triage4s.*

/** Stopping queued triage after the current issue (#14), with a latch-controlled fake classifier. */
class StopRunSpec extends AnyFunSuite:
  private val taxonomy = Taxonomy.copilotLens
  private def issue(id: Int): Issue =
    val input = TriageInput(id.toString, "CLI crashes", "Launcher crashes.")
    Issue(
      input,
      s"https://github.com/pavanvamsi3/copilot-lens/issues/$id",
      "open",
      Vector.empty,
      Instant.now().toString,
      Issue.hashInput(input)
    )
  private val response = TriageResponse(
    TriageResult(
      Some("bug"),
      Some("cli"),
      "The CLI fails.",
      Vector(Evidence("issueType", "title", "crashes"), Evidence("component", "title", "CLI")),
      false,
      Vector.empty
    ),
    "test-model",
    None
  )

  /** A classifier that blocks on its first call until `release` opens, counting calls. */
  private final class Gated:
    val calls = new AtomicInteger()
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val classifier: Classifier = new Classifier:
      def triage(input: TriageInput, t: Taxonomy) =
        if calls.incrementAndGet() == 1 then
          entered.countDown()
          release.await(5, TimeUnit.SECONDS)
        Right(response)

  private def service(c: Classifier, issues: Int = 3) =
    val s = new RunService(
      (_, _) => Right((1 to issues).map(issue).toVector),
      new SnapshotStore(Files.createTempDirectory("stop-")),
      () => Right(c),
      taxonomy
    )
    s.load("live", "open", 1)
    s

  private def awaitStatus(s: RunService, wanted: String): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while s.currentRun("status").str != wanted && System.nanoTime() < deadline do Thread.sleep(5)
    assert(s.currentRun("status").str == wanted)

  test(
    "no further call starts once a stop is accepted; the in-flight issue finishes and the rest are skipped"
  ):
    val gate = new Gated
    val s = service(gate.classifier)
    try
      val run = s.start(Vector("1", "2", "3"), true).toOption.get
      assert(gate.entered.await(5, TimeUnit.SECONDS))
      val stopping = s.stop(run("id").str).toOption.get
      assert(stopping("status").str == "running")
      assert(stopping("notice").str.contains("Stopping after the current issue"))
      gate.release.countDown()
      awaitStatus(s, "stopped")
      val items = s.currentRun("items").arr
      assert(items.map(_("status").str).toSeq == Seq("suggested", "skipped", "skipped"))
      assert(items(0).obj.contains("response"))
      assert(gate.calls.get == 1)
      assert(s.currentRun("notice").str.contains("after 1 of 3"))
    finally s.close()

  test("stop is idempotent, and answers a stopped run as it stands"):
    val gate = new Gated
    val s = service(gate.classifier)
    try
      val id = s.start(Vector("1", "2"), true).toOption.get("id").str
      assert(gate.entered.await(5, TimeUnit.SECONDS))
      assert(s.stop(id).isRight && s.stop(id).isRight)
      gate.release.countDown()
      awaitStatus(s, "stopped")
      assert(s.stop(id).toOption.get("status").str == "stopped")
      assert(gate.calls.get == 1)
    finally s.close()

  test("no new run starts while the stopped run's in-flight call is still running"):
    val gate = new Gated
    val s = service(gate.classifier)
    try
      val id = s.start(Vector("1", "2"), true).toOption.get("id").str
      assert(gate.entered.await(5, TimeUnit.SECONDS))
      s.stop(id)
      assert(s.start(Vector("2"), true).left.toOption.exists(_.contains("already active")))
      gate.release.countDown()
      awaitStatus(s, "stopped")
      // a later run is an explicit action, with consent, and it runs normally
      assert(s.start(Vector("2"), false).isLeft)
      assert(s.start(Vector("2"), true).isRight)
      awaitStatus(s, "completed")
    finally s.close()

  test("an old run's id cannot stop a newer run"):
    val calls = new AtomicInteger()
    val instant = new Classifier:
      def triage(input: TriageInput, t: Taxonomy) = { calls.incrementAndGet(); Right(response) }
    val s = service(instant)
    try
      val first = s.start(Vector("1"), true).toOption.get("id").str
      awaitStatus(s, "completed")
      val second = s.start(Vector("2"), true).toOption.get("id").str
      assert(first != second)
      assert(s.stop(first).left.toOption.contains("That run is no longer the active run."))
      awaitStatus(s, "completed")
    finally s.close()

  test("a stopped run cannot be recorded as a replay"):
    val gate = new Gated
    val s = service(gate.classifier)
    try
      val id = s.start(Vector("1", "2"), true).toOption.get("id").str
      assert(gate.entered.await(5, TimeUnit.SECONDS))
      s.stop(id)
      gate.release.countDown()
      awaitStatus(s, "stopped")
      assert(s.record().left.toOption.exists(_.contains("A stopped run cannot be recorded")))
    finally s.close()

  test("stopping a completed run changes nothing, and there is nothing to stop before a run"):
    val s = service(new Classifier { def triage(i: TriageInput, t: Taxonomy) = Right(response) })
    try
      assert(
        s.stop("00000000-0000-0000-0000-000000000000").left.toOption.contains("There is no run to stop.")
      )
      val id = s.start(Vector("1"), true).toOption.get("id").str
      awaitStatus(s, "completed")
      assert(s.stop(id).toOption.get("status").str == "completed")
      assert(s.record().isRight)
    finally s.close()
