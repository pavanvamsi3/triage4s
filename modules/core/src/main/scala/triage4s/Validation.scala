package triage4s

import scala.util.Try

object Validation:
  val MaxInputCharacters = 20000
  private val ResultKeys =
    Set("issueType", "component", "rationale", "evidence", "needsReview", "reviewReasons")

  def validateInput(input: TriageInput): Either[TriageError, Unit] =
    if input.id.trim.isEmpty then Left(TriageError.InvalidInput("Input ID must not be empty."))
    else if input.title.trim.isEmpty && input.body.trim.isEmpty then
      Left(TriageError.InvalidInput("Title and body must not both be empty."))
    else if input.title.length.toLong + input.body.length > MaxInputCharacters then
      Left(
        TriageError.InvalidInput(s"Title and body exceed $MaxInputCharacters characters; shorten explicitly.")
      )
    else Right(())

  def validateTaxonomy(taxonomy: Taxonomy): Either[TriageError, Unit] =
    val groups = Vector(taxonomy.types, taxonomy.components)
    if taxonomy.version.trim.isEmpty then Left(TriageError.InvalidTaxonomy("Taxonomy version is required."))
    else if groups.exists(g => g.isEmpty || g.size > 30) then
      Left(TriageError.InvalidTaxonomy("Each taxonomy group must contain 1 to 30 categories."))
    else if groups.exists(g => g.map(_.id).distinct.size != g.size) then
      Left(TriageError.InvalidTaxonomy("Category identifiers must be unique within each group."))
    else if groups.flatten.exists(c =>
        !c.id.matches("[a-z][a-z0-9-]{0,49}") || c.description.trim.isEmpty || c.description.length > 500
      )
    then
      Left(
        TriageError.InvalidTaxonomy(
          "Categories need a short lowercase ID and 1 to 500 description characters."
        )
      )
    else Right(())

  def decode(
      input: TriageInput,
      taxonomy: Taxonomy,
      text: String
  ): Either[TriageError, TriageResult] =
    if text.length > 30000 then Left(TriageError.InvalidOutput("Model output exceeds 30000 characters."))
    else
      val decoded = Try {
        val json = ujson.read(text)
        require(json.obj.keySet.toSet == ResultKeys)
        def optionalString(key: String): Option[String] =
          if json(key) == ujson.Null then None else Some(json(key).str)
        val evidence = json("evidence").arr.toVector.map { e =>
          require(e.obj.keySet.toSet == Set("target", "field", "quote"))
          Evidence(e("target").str, e("field").str, e("quote").str)
        }
        TriageResult(
          optionalString("issueType"),
          optionalString("component"),
          json("rationale").str,
          evidence,
          json("needsReview").bool,
          json("reviewReasons").arr.toVector.map(_.str)
        )
      }.toEither.left.map(_ =>
        TriageError.InvalidOutput("Model output must be a JSON object matching the complete triage schema.")
      )
      decoded.flatMap(validateResult(input, taxonomy, _))

  def validateResult(
      input: TriageInput,
      taxonomy: Taxonomy,
      result: TriageResult
  ): Either[TriageError, TriageResult] =
    def invalid(detail: String) = Left(TriageError.InvalidOutput(detail))
    for
      _ <- validateInput(input)
      _ <- validateTaxonomy(taxonomy)
      checked <-
        if result.issueType.exists(id => !taxonomy.types.exists(_.id == id)) ||
          result.component.exists(id => !taxonomy.components.exists(_.id == id))
        then invalid("Model returned a category outside the configured taxonomy.")
        else if result.rationale.trim.isEmpty || result.rationale.length > 2000 then
          invalid("A concise, nonempty rationale is required.")
        else if result.reviewReasons.size > 10 ||
          result.reviewReasons.exists(r => r.trim.isEmpty || r.length > 500)
        then invalid("Review reasons must be nonempty and bounded.")
        else if result.needsReview != result.reviewReasons.nonEmpty then
          invalid("Human-review status and review reasons must agree.")
        else if !result.needsReview && (result.issueType.isEmpty || result.component.isEmpty) then
          invalid("An unset classification requires human review.")
        else if result.evidence.size > 10 || result.evidence.exists { e =>
            val source = e.field match
              case "title" => Some(input.title)
              case "body"  => Some(input.body)
              case _       => None
            val targetExists = e.target match
              case "issueType" => result.issueType.nonEmpty
              case "component" => result.component.nonEmpty
              case _           => false
            !targetExists || e.quote.trim.isEmpty || e.quote.length > 2000 ||
            !source.exists(_.contains(e.quote))
          }
        then
          invalid(
            "Evidence must quote the specified source field exactly and support a present classification."
          )
        else if (result.issueType.nonEmpty && !result.evidence.exists(_.target == "issueType")) ||
          (result.component.nonEmpty && !result.evidence.exists(_.target == "component"))
        then invalid("Every proposed classification requires source evidence.")
        else Right(result)
    yield checked
