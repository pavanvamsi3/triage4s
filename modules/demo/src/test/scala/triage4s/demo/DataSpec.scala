package triage4s.demo

import org.scalatest.funsuite.AnyFunSuite
import triage4s.*
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.{Flow, TimeoutException}
import scala.jdk.CollectionConverters.*

class DataSpec extends AnyFunSuite:
  test("HTTP subscriber caps bytes during delivery, cancels oversized streams and completes valid bodies"):
    var cancelled = false
    var demand = 0L
    val subscription = new Flow.Subscription:
      override def request(count: Long): Unit = demand += count
      override def cancel(): Unit = cancelled = true
    val bounded = new GitHubSource.BoundedBody
    bounded.onSubscribe(subscription)
    bounded.onNext(java.util.List.of(ByteBuffer.wrap(new Array[Byte](GitHubSource.MaxResponseBytes))))
    assert(!cancelled)
    bounded.onNext(java.util.List.of(ByteBuffer.wrap(Array[Byte](1))))
    assert(cancelled)
    assert(bounded.getBody().toCompletableFuture.isCompletedExceptionally)
    assert(demand == 2)
    val valid = new GitHubSource.BoundedBody
    valid.onSubscribe(subscription)
    valid.onNext(java.util.List.of(ByteBuffer.wrap("[]".getBytes(UTF_8))))
    valid.onComplete()
    assert(new String(valid.getBody().toCompletableFuture.join(), UTF_8) == "[]")

  private val timestamp = "2026-09-13T21:00:00Z"
  private val taxonomy = Taxonomy.copilotLens
  private val input = TriageInput("187", "CLI crashes", "Launcher crashes.")
  private val issue = Issue(
    input,
    "https://github.com/pavanvamsi3/copilot-lens/issues/187",
    "open",
    Vector("good first issue"),
    timestamp,
    Issue.hashInput(input)
  )
  private val result = TriageResult(
    Some("bug"),
    Some("cli"),
    "CLI fails.",
    Vector(Evidence("issueType", "title", "crashes"), Evidence("component", "title", "CLI")),
    false,
    Vector.empty
  )
  // Synthetic transport/storage fixtures only; never saved as authentic model results.
  private val replay = Replay(
    1,
    timestamp,
    taxonomy,
    Triage.PromptVersion,
    Vector(RecordedIssue(issue, TriageResponse(result, "test-model", Some(TokenUsage(10, 5, 15)))))
  )

  private def sourceIssue(number: Int, state: String = "open"): ujson.Obj =
    ujson.Obj(
      "number" -> number,
      "title" -> input.title,
      "body" -> input.body,
      "html_url" -> s"https://github.com/${GitHubSource.Repository}/issues/$number",
      "state" -> state,
      "labels" -> ujson.Arr(ujson.Obj("name" -> "good first issue"))
    )

  private def response(value: ujson.Value): GitHubSource.Response =
    GitHubSource.Response(200, Map.empty, value.render().getBytes(UTF_8))

  private def withStore(f: (SnapshotStore, Path) => Unit): Unit =
    val parent = Path.of("target", "data-spec")
    Files.createDirectories(parent)
    val root = parent.resolve(java.util.UUID.randomUUID().toString)
    Files.createDirectory(root)
    try f(new SnapshotStore(root), root)
    finally
      val walk = Files.walk(root)
      try walk.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally walk.close()

  test("hash covers the input ID, title and body with unambiguous serialization"):
    assert(Issue.hashInput(input).matches("[a-f0-9]{64}"))
    assert(Issue.hashInput(input) == Issue.hashInput(input.copy()))
    assert(Issue.hashInput(input) != Issue.hashInput(input.copy(id = "188")))
    assert(
      Issue.hashInput(input.copy(title = "ab", body = "c")) !=
        Issue.hashInput(input.copy(title = "a", body = "bc"))
    )

  test("reject invalid state and page bounds without making any request"):
    val source = new GitHubSource(None, _ => fail("Must not fetch"))
    assert(source.load("all", 1).isLeft)
    assert(source.load("OPEN", 1).isLeft)
    assert(source.load("open", 0).isLeft)
    assert(source.load("closed", 4).isLeft)

  test("fixed GET URLs, finite timeout, authentication, bounded pages and PR filtering"):
    var requests = Vector.empty[HttpRequest]
    val source = new GitHubSource(
      Some("synthetic-token"),
      request =>
        requests :+= request
        val number = requests.size
        val pr = sourceIssue(100 + number, "closed")
        pr("pull_request") = ujson.Obj("url" -> "https://example.invalid")
        response(ujson.Arr(sourceIssue(number, "closed"), pr))
    )
    val loaded = source.load("closed", 3).toOption.get
    assert(loaded.map(_.input.id) == Vector("1", "2", "3"))
    assert(requests.size == 3)
    requests.zipWithIndex.foreach { (request, index) =>
      assert(request.method() == "GET")
      assert(
        request.uri().toString ==
          s"https://api.github.com/repos/pavanvamsi3/copilot-lens/issues?state=closed&per_page=30&page=${index + 1}"
      )
      assert(request.timeout().get() == GitHubSource.Timeout)
      assert(request.headers().firstValue("Authorization").get() == "Bearer synthetic-token")
    }
    assert(loaded.forall(i => i.hash == Issue.hashInput(i.input)))
    assert(loaded.forall(_.labels == Vector("good first issue")))

  test("empty body is accepted and a page without credentials sends no Authorization"):
    val entry = sourceIssue(187)
    entry("body") = ujson.Null
    val source = new GitHubSource(
      None,
      request =>
        assert(request.headers().firstValue("Authorization").isEmpty)
        response(ujson.Arr(entry))
    )
    assert(source.load("open", 1).toOption.get.head.input.body == "")

  test("visible HTTP and rate limit failures never expose response bodies or unsafe headers"):
    Vector(301, 401, 403, 404, 429, 500).foreach { status =>
      val source = new GitHubSource(
        None,
        _ =>
          GitHubSource.Response(
            status,
            Map("X-RateLimit-Remaining" -> "0", "X-RateLimit-Reset" -> "secret-body"),
            "secret-body".getBytes(UTF_8)
          )
      )
      val error = source.load("open", 1).left.toOption.get
      assert(!error.contains("secret-body"))
      if status == 403 || status == 429 then assert(error.contains("rate limit"))
      else assert(error.contains(status.toString))
    }
    val limited = new GitHubSource(
      None,
      _ => GitHubSource.Response(429, Map("x-ratelimit-reset" -> "1789336800"), Array.emptyByteArray)
    )
    assert(limited.load("open", 1).left.toOption.get.contains("1789336800"))

  test("network errors are redacted, timeouts explicit, failed page prevents further requests"):
    val failed = new GitHubSource(Some("secret-token"), _ => throw new RuntimeException("secret-token"))
    assert(!failed.load("open", 1).left.toOption.get.contains("secret-token"))
    val timeout = new GitHubSource(None, _ => throw new TimeoutException("secret"))
    assert(timeout.load("open", 1).left.toOption.get.contains("timed out"))
    var calls = 0
    val pages = new GitHubSource(
      None,
      _ =>
        calls += 1
        if calls == 1 then response(ujson.Arr(sourceIssue(187)))
        else GitHubSource.Response(500, Map.empty, Array.emptyByteArray)
    )
    assert(pages.load("open", 3).isLeft)
    assert(calls == 2)

  test("reject oversized, malformed, overfull and duplicate issue pages"):
    val oversized = new GitHubSource(
      None,
      _ => GitHubSource.Response(200, Map.empty, new Array[Byte](GitHubSource.MaxResponseBytes + 1))
    )
    assert(oversized.load("open", 1).left.toOption.get.contains("4 MiB"))
    val malformed = new GitHubSource(None, _ => GitHubSource.Response(200, Map.empty, "{".getBytes(UTF_8)))
    assert(malformed.load("open", 1).isLeft)
    val overfull = new GitHubSource(None, _ => response(ujson.Arr.from((1 to 31).map(sourceIssue(_)))))
    assert(overfull.load("open", 1).isLeft)
    val duplicate = new GitHubSource(None, _ => response(ujson.Arr(sourceIssue(187))))
    assert(duplicate.load("open", 2).left.toOption.get.contains("duplicate"))
    val wrongState = new GitHubSource(None, _ => response(ujson.Arr(sourceIssue(187, "closed"))))
    assert(wrongState.load("open", 1).isLeft)

  test("snapshot and replay roundtrip use fixed paths and private permissions"):
    withStore { (store, root) =>
      assert(store.saveIssues(Vector(issue)) == Right(()))
      assert(store.loadIssues() == Right(Vector(issue)))
      assert(store.saveReplay(replay) == Right(()))
      assert(store.loadReplay(taxonomy) == Right(replay))
      Vector("issues", "replay").foreach { kind =>
        val folder = root.resolve(s"fixtures/$kind")
        assert(Files.getPosixFilePermissions(folder) == PosixFilePermissions.fromString("rwx------"))
        assert(
          Files.getPosixFilePermissions(folder.resolve("latest.json")) ==
            PosixFilePermissions.fromString("rw-------")
        )
        val listing = Files.list(folder)
        try assert(listing.count() == 1)
        finally listing.close()
      }
      assert(store.saveIssues(Vector(issue.copy(state = "closed"))) == Right(()))
      assert(store.loadIssues().toOption.get.head.state == "closed")
    }

  test("missing or invalid files are explicit errors, with no fabricated fallback"):
    withStore { (store, root) =>
      assert(store.loadIssues().left.toOption.get.contains("Missing"))
      assert(store.loadReplay(taxonomy).left.toOption.get.contains("Missing"))
      store.saveIssues(Vector(issue))
      val path = root.resolve("fixtures/issues/latest.json")
      Files.writeString(path, """{"secret":"malformed-body"}""")
      val error = store.loadIssues().left.toOption.get
      assert(error.contains("invalid"))
      assert(!error.contains("malformed-body"))
      Files.write(path, new Array[Byte](8 * 1024 * 1024 + 1))
      assert(store.loadIssues().left.toOption.get.contains("8 MiB"))
    }

  test("reject hashes, foreign URLs, source fields, duplicate IDs and oversized inputs on save"):
    withStore { (store, _) =>
      Vector(
        issue.copy(hash = "bad"),
        issue.copy(url = "https://github.com/other/repo/issues/187"),
        issue.copy(state = "all"),
        issue.copy(retrievedAt = "yesterday"),
        issue.copy(labels = Vector("")),
        issue.copy(labels = Vector("bug", "bug")),
        issue.copy(input = input.copy(id = "../187")),
        issue.copy(input = input.copy(title = ""))
      ).foreach { invalid =>
        assert(store.saveIssues(Vector(invalid)).isLeft)
      }
      assert(store.saveIssues(Vector(issue, issue)).isLeft)
      val large = input.copy(body = "x" * 20001)
      assert(store.saveIssues(Vector(issue.copy(input = large, hash = Issue.hashInput(large)))).isLeft)
      assert(store.loadIssues().isLeft)
    }

  test("tampered hashes and duplicate IDs are also rejected on snapshot load"):
    withStore { (store, root) =>
      store.saveIssues(Vector(issue))
      val path = root.resolve("fixtures/issues/latest.json")
      val json = ujson.read(Files.readString(path))
      json("issues")(0)("input")("body") = "tampered"
      Files.writeString(path, json.render())
      assert(store.loadIssues().left.toOption.get.contains("hash"))
      store.saveIssues(Vector(issue))
      val duplicate = ujson.read(Files.readString(path))
      duplicate("issues").arr += upickle.default.writeJs(issue)
      Files.writeString(path, duplicate.render())
      assert(store.loadIssues().left.toOption.get.contains("duplicate"))
    }

  test("null source and replay fields return invalid-file errors rather than throwing"):
    withStore { (store, root) =>
      assert(store.saveIssues(Vector(issue)).isRight)
      val path = root.resolve("fixtures/issues/latest.json")
      val original = Files.readString(path)
      Vector("input", "retrievedAt", "labels", "hash").foreach { field =>
        val json = ujson.read(original)
        json("issues")(0)(field) = ujson.Null
        Files.writeString(path, json.render())
        assert(store.loadIssues().isLeft)
      }
      assert(store.saveReplay(replay).isRight)
      val recording = root.resolve("fixtures/replay/latest.json")
      val json = ujson.read(Files.readString(recording))
      json("items")(0)("response")("result")("rationale") = ujson.Null
      Files.writeString(recording, json.render())
      assert(store.loadReplay(taxonomy).isLeft)
    }

  test("replay validates schema, exact taxonomy, prompt, timestamp, evidence and response metadata"):
    withStore { (store, root) =>
      val invalidResult = replay.items.head.copy(response =
        replay.items.head.response
          .copy(result = result.copy(evidence = Vector(Evidence("issueType", "body", "fabricated"))))
      )
      val invalidModel = replay.items.head.copy(response = replay.items.head.response.copy(model = ""))
      val invalidUsage =
        replay.items.head.copy(response = replay.items.head.response.copy(usage = Some(TokenUsage(1, 1, 99))))
      Vector(
        replay.copy(schemaVersion = 99),
        replay.copy(promptVersion = "other"),
        replay.copy(capturedAt = "invalid"),
        replay.copy(items = Vector.empty),
        replay.copy(items = Vector(invalidResult)),
        replay.copy(items = Vector(invalidModel)),
        replay.copy(items = Vector(invalidUsage)),
        replay.copy(items = replay.items ++ replay.items)
      )
        .foreach(invalid => assert(store.saveReplay(invalid).isLeft))
      assert(store.saveReplay(replay).isRight)
      assert(store.loadReplay(taxonomy.copy(version = "other")).isLeft)
      assert(store.loadReplay(taxonomy.copy(types = taxonomy.types.reverse)).isLeft)
      val path = root.resolve("fixtures/replay/latest.json")
      Files.writeString(path, upickle.default.write(replay.copy(items = Vector(invalidResult))))
      assert(store.loadReplay(taxonomy).left.toOption.get.contains("evidence"))
    }

  test("refuse symlinked files and directories without overwriting their destinations"):
    withStore { (store, root) =>
      store.saveIssues(Vector(issue))
      val target = root.resolve("fixtures/issues/latest.json")
      val unrelated = root.resolve("unrelated.json")
      Files.writeString(unrelated, "keep")
      Files.delete(target)
      Files.createSymbolicLink(target, unrelated.toAbsolutePath)
      assert(store.loadIssues().isLeft)
      assert(store.saveIssues(Vector(issue)).isLeft)
      assert(Files.readString(unrelated) == "keep")
      Files.delete(target)
      Files.delete(target.getParent)
      Files.createSymbolicLink(target.getParent, root.toAbsolutePath)
      assert(store.saveIssues(Vector(issue)).isLeft)
    }
