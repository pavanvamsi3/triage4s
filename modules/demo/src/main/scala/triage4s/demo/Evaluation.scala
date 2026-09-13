package triage4s.demo

import java.nio.file.{Files, Path}
import triage4s.Taxonomy
import scala.util.Try
import upickle.default.*

final case class Expected(
    id: String,
    inputHash: String,
    acceptedTypes: Vector[Option[String]],
    acceptedComponents: Vector[Option[String]],
    needsReview: Boolean,
    split: String,
    reviewedBy: String
) derives ReadWriter

object Evaluation:
  def score(replay: Replay, expected: Vector[Expected], split: String): Either[String, ujson.Value] =
    val selected = expected.filter(_.split == split)
    val byId = replay.items.map(item => item.issue.input.id -> item).toMap
    if !Set("development", "heldout").contains(split) then Left("Split must be development or heldout.")
    else if expected.map(_.id).distinct.size != expected.size then Left("Expected issue IDs must be unique.")
    else if expected.exists(e => !Set("development", "heldout").contains(e.split)) then
      Left("Every annotation must declare a development or heldout split.")
    else if selected.isEmpty then Left(s"No annotated examples for split $split.")
    else if selected.exists(e =>
        e.reviewedBy.trim.isEmpty || e.acceptedTypes.isEmpty || e.acceptedComponents.isEmpty
      )
    then Left("Every selected example needs human-reviewed acceptable outcomes and a reviewer name.")
    else if selected.exists(e =>
        e.acceptedTypes.flatten.exists(id => !replay.taxonomy.types.exists(_.id == id)) ||
          e.acceptedComponents.flatten.exists(id => !replay.taxonomy.components.exists(_.id == id))
      )
    then Left("Annotations contain categories outside the recorded taxonomy.")
    else if selected.exists(e => byId.get(e.id).exists(_.issue.hash != e.inputHash)) then
      Left("An annotation input hash does not match the recorded issue.")
    else
      val outcomes = selected.map { e =>
        byId.get(e.id).map { item =>
          val result = item.response.result
          val typeMatch = e.acceptedTypes.contains(result.issueType)
          val componentMatch = e.acceptedComponents.contains(result.component)
          val reviewMatch = e.needsReview == result.needsReview
          (
            typeMatch,
            componentMatch,
            reviewMatch,
            result.needsReview,
            result.issueType.isEmpty || result.component.isEmpty
          )
        }
      }
      val total = selected.size
      val completed = outcomes.flatten
      def metric(count: Int) =
        ujson.Obj("count" -> count, "total" -> total, "fraction" -> count.toDouble / total)
      Right(
        ujson.Obj(
          "split" -> split,
          "expected" -> total,
          "recorded" -> completed.size,
          "missingResults" -> (total - completed.size),
          "typeAccuracy" -> metric(completed.count(_._1)),
          "componentAccuracy" -> metric(completed.count(_._2)),
          "reviewDecisionAccuracy" -> metric(completed.count(_._3)),
          "exactMatch" -> metric(completed.count(r => r._1 && r._2 && r._3)),
          "needsReview" -> metric(completed.count(_._4)),
          "abstentions" -> metric(completed.count(_._5)),
          "coverage" -> metric(completed.size),
          "note" -> "Missing results remain in all accuracy denominators. Replay contains successful responses only; missing is not necessarily a provider error."
        )
      )

  def main(args: Array[String]): Unit =
    val outcome = for
      _ <- Either.cond(args.length == 2, (), "Usage: Evaluation <expected-local.json> <development|heldout>")
      annotations <- Try {
        val path = Path.of(args(0))
        require(Files.size(path) <= 1024 * 1024)
        read[Vector[Expected]](Files.readString(path))
      }.toEither.left.map(_ => "Cannot read annotation file: expected a JSON array no larger than 1 MiB.")
      replay <- new SnapshotStore(Path.of(".")).loadReplay(Taxonomy.copilotLens)
      report <- score(replay, annotations, args(1))
    yield report
    outcome match
      case Right(report) => println(report.render(indent = 2))
      case Left(error)   =>
        System.err.println(error)
        sys.exit(1)
