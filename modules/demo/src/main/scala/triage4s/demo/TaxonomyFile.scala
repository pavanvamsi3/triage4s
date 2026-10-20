package triage4s.demo

import java.nio.file.{Files, Path}
import scala.util.Try
import triage4s.{Taxonomy, Validation}

/** A classification taxonomy read from a local JSON file at startup (`TRIAGE_TAXONOMY_FILE`, #13). */
object TaxonomyFile:

  /** The largest file read; a taxonomy of 2 x 30 categories with 500-character descriptions is well under it.
    */
  val MaxBytes: Long = 64 * 1024

  /** The taxonomy in `path`: a JSON object with `version`, `types` and `components`, each category an `id`
    * and a `description`. The size is checked before parsing and the result is validated with the same rules
    * as every taxonomy. Each error names the file.
    */
  def load(path: Path): Either[String, Taxonomy] =
    for
      _ <- Either.cond(Files.isRegularFile(path), (), s"TRIAGE_TAXONOMY_FILE: $path is not a readable file.")
      size <- Try(Files.size(path)).toEither.left.map(_ =>
        s"TRIAGE_TAXONOMY_FILE: cannot read the size of $path."
      )
      _ <- Either.cond(
        size <= MaxBytes,
        (),
        s"TRIAGE_TAXONOMY_FILE: $path is larger than ${MaxBytes / 1024} KiB."
      )
      taxonomy <- Try(upickle.default.read[Taxonomy](Files.readString(path))).toEither.left.map(_ =>
        s"TRIAGE_TAXONOMY_FILE: $path is not a taxonomy: a JSON object with version, types and components."
      )
      _ <- Validation.validateTaxonomy(taxonomy).left.map(e => s"TRIAGE_TAXONOMY_FILE: $path: ${e.message}")
    yield taxonomy

  /** The taxonomy `TRIAGE_TAXONOMY_FILE` names in `environment`, or the built-in one when it is unset. */
  def fromEnvironment(environment: Map[String, String]): Either[String, Taxonomy] =
    environment.get("TRIAGE_TAXONOMY_FILE").map(_.trim).filter(_.nonEmpty) match
      case None       => Right(Taxonomy.copilotLens)
      case Some(file) =>
        Try(Path.of(file)).toEither.left.map(_ => "TRIAGE_TAXONOMY_FILE is not a valid path.").flatMap(load)
