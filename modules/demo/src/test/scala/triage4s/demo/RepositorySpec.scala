package triage4s.demo

import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import triage4s.*

/** The public GitHub repository chosen with TRIAGE_REPOSITORY (#12). */
class RepositorySpec extends AnyFunSuite:
  private val other = "scala/scala3"

  private def sourceIssue(repository: String, number: Int): ujson.Obj =
    ujson.Obj(
      "number" -> number,
      "title" -> "Compiler crashes",
      "body" -> "The compiler crashes on startup.",
      "html_url" -> s"https://github.com/$repository/issues/$number",
      "state" -> "open",
      "labels" -> ujson.Arr()
    )

  private def response(value: ujson.Value) =
    GitHubSource.Response(200, Map.empty, value.render().getBytes(UTF_8))

  test("an owner/name is accepted; URLs, paths, traversal and query strings are refused"):
    Seq("pavanvamsi3/copilot-lens", "scala/scala3", "a/b", "org-1/name.with_dots-and-dashes").foreach(r =>
      assert(GitHubSource.validRepository(r), r)
    )
    Seq(
      "",
      "copilot-lens",
      "https://github.com/scala/scala3",
      "github.com/scala/scala3",
      "scala/scala3/issues",
      "../scala3",
      "scala/..",
      "scala/.",
      "scala/scala3?state=all",
      "scala/scala3#1",
      "scala /scala3",
      "-scala/scala3",
      "o" * 40 + "/n",
      "o/" + "n" * 101
    ).foreach(r => assert(!GitHubSource.validRepository(r), r))

  test("TRIAGE_REPOSITORY is read and checked; unset means the default repository"):
    assert(AppConfig.load(Map.empty).toOption.get.repository == GitHubSource.Repository)
    assert(AppConfig.load(Map("TRIAGE_REPOSITORY" -> s" $other ")).toOption.get.repository == other)
    assert(
      AppConfig
        .load(Map("TRIAGE_REPOSITORY" -> "https://github.com/scala/scala3"))
        .left
        .toOption
        .exists(_.contains("owner/name"))
    )

  test("issues are fetched from the configured repository, and links are checked against it"):
    var requests = Vector.empty[HttpRequest]
    val source =
      new GitHubSource(None, r => { requests :+= r; response(ujson.Arr(sourceIssue(other, 7))) }, other)
    val loaded = source.load("open", 1).toOption.get
    assert(
      requests.head
        .uri()
        .toString == s"https://api.github.com/repos/$other/issues?state=open&per_page=30&page=1"
    )
    assert(loaded.map(_.url) == Vector(s"https://github.com/$other/issues/7"))
    val mixed =
      new GitHubSource(None, _ => response(ujson.Arr(sourceIssue(GitHubSource.Repository, 7))), other)
    assert(mixed.load("open", 1).left.toOption.exists(_.contains(s"configured repository ($other)")))

  test("an invalid repository is refused before any request"):
    intercept[IllegalArgumentException](new GitHubSource(None, _ => fail("Must not fetch"), "../etc"))

  test("a snapshot or recording of another repository is refused, never reused"):
    val root = Files.createTempDirectory("triage4s-repository-")
    val issues = new GitHubSource(None, _ => response(ujson.Arr(sourceIssue(other, 7))), other)
      .load("open", 1)
      .toOption
      .get
    val mine = new SnapshotStore(root, other)
    assert(mine.saveIssues(issues).isRight)
    assert(mine.loadIssues().map(_.map(_.input.id)) == Right(Vector("7")))
    val default = new SnapshotStore(root)
    assert(
      default.loadIssues().left.toOption.exists(e => e.contains(other) && e.contains(GitHubSource.Repository))
    )

    val result = TriageResult(
      Some("bug"),
      None,
      "The compiler crashes.",
      Vector(Evidence("issueType", "title", "crashes")),
      true,
      Vector("Component unclear.")
    )
    val replay = Replay(
      1,
      java.time.Instant.now().toString,
      Taxonomy.copilotLens,
      Triage.PromptVersion,
      Vector(RecordedIssue(issues.head, TriageResponse(result, "test-model", None)))
    )
    assert(mine.saveReplay(replay).isRight)
    assert(mine.loadReplay(Taxonomy.copilotLens).isRight)
    assert(default.loadReplay(Taxonomy.copilotLens).isLeft)
