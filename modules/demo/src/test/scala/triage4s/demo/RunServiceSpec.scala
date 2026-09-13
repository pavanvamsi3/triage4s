package triage4s.demo

import triage4s.*
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*

class RunServiceSpec extends AnyFunSuite:
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
  private def withStore(test: SnapshotStore => Unit): Unit =
    val root = Files.createTempDirectory("triage4s-run-test-")
    try test(new SnapshotStore(root))
    finally
      val files = Files.walk(root)
      try files.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally files.close()

  private def awaitCompleted(service: RunService): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while service.currentRun("status").str == "running" && System.nanoTime() < deadline do Thread.sleep(10)
    assert(service.currentRun("status").str == "completed")

  test("bounds selections, requires explicit consent, and rejects missing loaded IDs"):
    withStore { store =>
      val count = new AtomicInteger()
      val classifier = new Classifier:
        override def triage(input: TriageInput, tax: Taxonomy) =
          count.incrementAndGet()
          Right(response)
      val service = new RunService(
        (_, _) => Right((1 to 11).map(issue).toVector),
        store,
        () => Right(classifier),
        taxonomy
      )
      try
        assert(service.load("live", "open", 1).isRight)
        assert(service.start((1 to 11).map(_.toString).toVector, true).isLeft)
        assert(service.start(Vector("1"), false).isLeft)
        assert(service.start(Vector("1", "1"), true).isLeft)
        assert(service.start(Vector("999"), true).isLeft)
        assert(service.start(Vector.empty, true).isLeft)
        assert(count.get() == 0)
      finally service.close()
    }

  test("one issue at a time, overlapping runs rejected, failures do not stop later issues"):
    withStore { store =>
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val active = new AtomicInteger()
      val peak = new AtomicInteger()
      val calls = new AtomicInteger()
      val classifier = new Classifier:
        override def triage(input: TriageInput, tax: Taxonomy) =
          val concurrent = active.incrementAndGet()
          peak.updateAndGet(old => math.max(old, concurrent))
          calls.incrementAndGet()
          try
            if input.id == "1" then
              started.countDown()
              if !release.await(5, TimeUnit.SECONDS) then Left(TriageError.ProviderFailure("test timeout"))
              else Left(TriageError.ProviderFailure("provider unavailable"))
            else Right(response)
          finally active.decrementAndGet()
      val service =
        new RunService((_, _) => Right(Vector(issue(1), issue(2))), store, () => Right(classifier), taxonomy)
      try
        service.load("live", "open", 1)
        assert(service.start(Vector("1", "2"), true).isRight)
        assert(started.await(5, TimeUnit.SECONDS))
        assert(service.start(Vector("2"), true).isLeft)
        assert(service.load("snapshot", "open", 1).isLeft)
        release.countDown()
        awaitCompleted(service)
        val items = service.currentRun("items").arr
        assert(items.map(_("status").str).toVector == Vector("error", "suggested"))
        assert(calls.get() == 2)
        assert(peak.get() == 1)
        assert(service.record().isLeft)
      finally
        release.countDown()
        service.close()
    }

  test("exactly ten issues can complete and a subsequent manual run is allowed"):
    withStore { store =>
      val calls = new AtomicInteger()
      val classifier = new Classifier:
        override def triage(input: TriageInput, tax: Taxonomy) =
          calls.incrementAndGet()
          Right(response)
      val service = new RunService(
        (_, _) => Right((1 to 10).map(issue).toVector),
        store,
        () => Right(classifier),
        taxonomy
      )
      try
        service.load("live", "open", 1)
        assert(service.start((1 to 10).map(_.toString).toVector, true).isRight)
        awaitCompleted(service)
        assert(calls.get() == 10)
        assert(service.start(Vector("1"), true).isRight)
        awaitCompleted(service)
        assert(calls.get() == 11)
      finally service.close()
    }

  test("snapshot loads without credentials but cannot silently turn into replay"):
    withStore { store =>
      assert(store.saveIssues(Vector(issue(1))).isRight)
      val service = new RunService(
        (_, _) => fail("GitHub must not be called"),
        store,
        () => Left("OPENAI_API_KEY is not configured"),
        taxonomy
      )
      try
        assert(service.load("snapshot", "open", 1).isRight)
        assert(service.start(Vector("1"), true).left.toOption.get.contains("OPENAI_API_KEY"))
        assert(service.currentRun("status").str == "idle")
      finally service.close()
    }

  test("failed source changes invalidate previous selections instead of falling back"):
    withStore { store =>
      val service = new RunService(
        (_, _) => Right(Vector(issue(1))),
        store,
        () => fail("A failed source change must not execute a model"),
        taxonomy
      )
      try
        assert(service.load("live", "open", 1).isRight)
        assert(service.load("replay", "open", 1).isLeft)
        assert(service.start(Vector("1"), true).isLeft)
      finally service.close()
    }

  test("validated recorded replay uses neither a provider factory nor GitHub"):
    withStore { store =>
      // Synthetic test fixture, never included in the authentic demo.
      val replay = Replay(
        1,
        Instant.now().toString,
        taxonomy,
        Triage.PromptVersion,
        Vector(RecordedIssue(issue(1), response))
      )
      assert(store.saveReplay(replay).isRight)
      val service = new RunService(
        (_, _) => fail("No GitHub calls during replay"),
        store,
        () => fail("No model construction during replay"),
        taxonomy
      )
      try
        assert(service.load("replay", "open", 1).isRight)
        assert(service.start(Vector("1"), false).isRight)
        assert(service.currentRun("mode").str == "replay")
        assert(service.currentRun("status").str == "completed")
        assert(service.currentRun("notice").str.contains(replay.capturedAt))
        assert(service.record().isLeft)
      finally service.close()
    }
