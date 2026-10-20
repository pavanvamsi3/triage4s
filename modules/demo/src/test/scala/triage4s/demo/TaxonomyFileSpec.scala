package triage4s.demo

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import triage4s.*

/** A custom taxonomy loaded at startup from TRIAGE_TAXONOMY_FILE (#13). */
class TaxonomyFileSpec extends AnyFunSuite:
  private def file(content: String): Path =
    val path = Files.createTempFile("taxonomy", ".json")
    Files.writeString(path, content)
    path

  private val custom = Taxonomy(
    "mine-v1",
    Vector(Category("bug", "Broken behaviour.")),
    Vector(Category("api", "The public HTTP API."))
  )
  private def customFile = file(upickle.default.write(custom))

  /** The repository root, from the module directory or the root itself. */
  private lazy val root: Path =
    Iterator
      .iterate(Path.of(".").toAbsolutePath.normalize)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isRegularFile(p.resolve("examples/taxonomy.json")))
      .get

  test("without TRIAGE_TAXONOMY_FILE the built-in taxonomy is used"):
    assert(TaxonomyFile.fromEnvironment(Map.empty) == Right(Taxonomy.copilotLens))
    assert(TaxonomyFile.fromEnvironment(Map("TRIAGE_TAXONOMY_FILE" -> "  ")) == Right(Taxonomy.copilotLens))
    assert(AppConfig.load(Map.empty).toOption.get.taxonomy == Taxonomy.copilotLens)

  test("a valid file is loaded, and AppConfig carries it"):
    val path = customFile
    assert(TaxonomyFile.load(path) == Right(custom))
    assert(AppConfig.load(Map("TRIAGE_TAXONOMY_FILE" -> path.toString)).toOption.get.taxonomy == custom)

  test("the documented example is a valid taxonomy with its own version"):
    val example = TaxonomyFile.load(root.resolve("examples/taxonomy.json")).toOption.get
    assert(example.version == "example-webshop-v1")
    assert(example.types.nonEmpty && example.components.nonEmpty)

  test("a broken file fails clearly, naming the file, and never falls back to the built-in taxonomy"):
    val cases = Seq(
      file("not json") -> "is not a taxonomy",
      file("""{"version":"v1"}""") -> "is not a taxonomy",
      file(upickle.default.write(custom.copy(version = " "))) -> "TRIAGE_TAXONOMY_FILE",
      file(upickle.default.write(custom.copy(types = Vector.empty))) -> "1 to 30 categories",
      file(
        upickle.default.write(custom.copy(types = custom.types ++ custom.types))
      ) -> "TRIAGE_TAXONOMY_FILE",
      file(upickle.default.write(custom.copy(types = Vector(Category("Bad ID", "x"))))) -> "lowercase ID",
      file("x" * (TaxonomyFile.MaxBytes.toInt + 1)) -> "larger than 64 KiB"
    )
    cases.foreach { (path, expected) =>
      val error = TaxonomyFile.load(path).left.toOption.get
      assert(error.contains(expected), error)
      assert(error.contains(path.toString), error)
      assert(AppConfig.load(Map("TRIAGE_TAXONOMY_FILE" -> path.toString)).isLeft)
    }
    val missing = Path.of("/no/such/taxonomy.json")
    assert(TaxonomyFile.load(missing).left.toOption.exists(_.contains("is not a readable file")))

  test("the configured taxonomy is the one the classifier receives"):
    val seen = new java.util.concurrent.atomic.AtomicReference[Taxonomy]()
    val classifier = new Classifier:
      def triage(input: TriageInput, taxonomy: Taxonomy) =
        seen.set(taxonomy)
        Left(TriageError.ProviderFailure("not needed"))
    val root = Files.createTempDirectory("triage4s-taxonomy-")
    val input = TriageInput("1", "API fails", "The endpoint returns 500.")
    val issue = Issue(
      input,
      "https://github.com/pavanvamsi3/copilot-lens/issues/1",
      "open",
      Vector.empty,
      "now",
      Issue.hashInput(input)
    )
    val service =
      new RunService((_, _) => Right(Vector(issue)), new SnapshotStore(root), () => Right(classifier), custom)
    try
      service.load("live", "open", 1)
      assert(service.start(Vector("1"), true).isRight)
      val deadline = System.nanoTime() + 5_000_000_000L
      while service.currentRun("status").str == "running" && System.nanoTime() < deadline do Thread.sleep(10)
      assert(seen.get == custom)
    finally service.close()

  test("a recording made with another taxonomy is rejected, not remapped"):
    val replay = Replay(1, "now", Taxonomy.copilotLens, "v1", Vector.empty)
    assert(DataValidation.replay(replay, custom).left.toOption.exists(_.contains("does not match")))
