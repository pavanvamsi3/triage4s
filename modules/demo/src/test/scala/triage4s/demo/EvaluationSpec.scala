package triage4s.demo

import org.scalatest.funsuite.AnyFunSuite
import triage4s.*
import java.time.Instant

class EvaluationSpec extends AnyFunSuite:
  private val input = TriageInput("1", "CLI", "Unclear request")
  private val issue = Issue(
    input,
    "https://github.com/pavanvamsi3/copilot-lens/issues/1",
    "open",
    Vector.empty,
    Instant.now().toString,
    Issue.hashInput(input)
  )
  private val result = TriageResult(
    None,
    Some("cli"),
    "Type is unclear.",
    Vector(Evidence("component", "title", "CLI")),
    true,
    Vector("Insufficient context.")
  )
  private val replay = Replay(
    1,
    Instant.now().toString,
    Taxonomy.copilotLens,
    Triage.PromptVersion,
    Vector(RecordedIssue(issue, TriageResponse(result, "test-only", None)))
  )
  private val expected =
    Expected("1", issue.hash, Vector(None), Vector(Some("cli")), true, "heldout", "test reviewer")

  test("reports abstentions and missing outcomes in denominators"):
    val report = Evaluation.score(replay, Vector(expected, expected.copy(id = "2")), "heldout").toOption.get
    assert(report("expected").num == 2)
    assert(report("missingResults").num == 1)
    assert(report("typeAccuracy")("fraction").num == 0.5)
    assert(report("abstentions")("count").num == 1)
    assert(report("coverage")("fraction").num == 0.5)

  test("requires human annotation, nonempty evaluation split and matching source hashes"):
    assert(Evaluation.score(replay, Vector(expected.copy(reviewedBy = "")), "heldout").isLeft)
    assert(Evaluation.score(replay, Vector(expected.copy(inputHash = "wrong")), "heldout").isLeft)
    assert(Evaluation.score(replay, Vector(expected), "development").isLeft)
    assert(Evaluation.score(replay, Vector(expected, expected), "heldout").isLeft)
