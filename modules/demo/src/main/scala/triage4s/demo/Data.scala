package triage4s.demo

import triage4s.*
import upickle.default.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.{Duration, Instant}
import java.util.concurrent.{CompletableFuture, CompletionStage, Flow, TimeUnit, TimeoutException}
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

final case class Issue(
    input: TriageInput,
    url: String,
    state: String,
    labels: Vector[String],
    retrievedAt: String,
    hash: String
) derives ReadWriter

object Issue:
  def hashInput(input: TriageInput): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(write(input).getBytes(UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

final case class RecordedIssue(issue: Issue, response: TriageResponse) derives ReadWriter
final case class Replay(
    schemaVersion: Int,
    capturedAt: String,
    taxonomy: Taxonomy,
    promptVersion: String,
    items: Vector[RecordedIssue]
) derives ReadWriter

object GitHubSource:
  val Repository = "pavanvamsi3/copilot-lens"
  val MaxResponseBytes = 4 * 1024 * 1024
  val Timeout: Duration = Duration.ofSeconds(20)
  final case class Response(status: Int, headers: Map[String, String], body: Array[Byte])
  type Fetch = HttpRequest => Response

  private class ResponseTooLarge extends RuntimeException

  // The cap applies while receiving bytes, not after an unbounded body has been allocated.
  private[demo] class BoundedBody extends HttpResponse.BodySubscriber[Array[Byte]]:
    private val result = new CompletableFuture[Array[Byte]]()
    private val bytes = new ByteArrayOutputStream()
    private var subscription: Flow.Subscription = uninitialized
    override def getBody(): CompletionStage[Array[Byte]] = result
    override def onSubscribe(value: Flow.Subscription): Unit =
      subscription = value
      value.request(1)
    override def onNext(buffers: java.util.List[ByteBuffer]): Unit =
      val incoming = buffers.asScala.map(_.remaining().toLong).sum
      if bytes.size().toLong + incoming > MaxResponseBytes then
        subscription.cancel()
        result.completeExceptionally(new ResponseTooLarge)
      else
        buffers.asScala.foreach { buffer =>
          val chunk = new Array[Byte](buffer.remaining())
          buffer.get(chunk)
          bytes.write(chunk)
        }
        subscription.request(1)
    override def onError(error: Throwable): Unit = { result.completeExceptionally(error); () }
    override def onComplete(): Unit = { result.complete(bytes.toByteArray); () }

  private lazy val client = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  private def fetch(request: HttpRequest): Response =
    val pending = client.sendAsync(request, (_: HttpResponse.ResponseInfo) => new BoundedBody)
    try
      val response = pending.get(Timeout.toMillis, TimeUnit.MILLISECONDS)
      Response(
        response.statusCode(),
        response.headers().map().asScala.map((key, values) => key -> values.asScala.mkString(",")).toMap,
        response.body()
      )
    finally if !pending.isDone then pending.cancel(true)

  private def failure(error: Throwable): String =
    val causes = Iterator.iterate(error)(_.getCause).takeWhile(_ != null).take(8).toVector
    if causes.exists(_.isInstanceOf[ResponseTooLarge]) then "GitHub response exceeds the 4 MiB limit."
    else if causes.exists(e => e.isInstanceOf[TimeoutException] || e.isInstanceOf[HttpTimeoutException]) then
      "GitHub request timed out."
    else "GitHub request failed (network or transport error)."

class GitHubSource(token: Option[String], fetch: GitHubSource.Fetch = GitHubSource.fetch):
  import GitHubSource.*

  def load(state: String, pages: Int): Either[String, Vector[Issue]] =
    if !Set("open", "closed").contains(state) then Left("GitHub state must be open or closed.")
    else if pages < 1 || pages > 3 then Left("GitHub pages must be between 1 and 3 (30 entries per page).")
    else
      def page(number: Int): Either[String, Vector[Issue]] =
        val loaded = Try {
          val builder = HttpRequest
            .newBuilder(
              URI.create(
                s"https://api.github.com/repos/$Repository/issues?state=$state&per_page=30&page=$number"
              )
            )
            .timeout(Timeout)
            .GET()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "triage4s-read-only-demo")
          token.foreach(value => builder.header("Authorization", s"Bearer $value"))
          fetch(builder.build())
        }.toEither.left.map(failure)
        loaded.flatMap { response =>
          val headers = response.headers.map((key, value) => key.toLowerCase(java.util.Locale.ROOT) -> value)
          if response.status == 429 || (response.status == 403 && headers
              .get("x-ratelimit-remaining")
              .contains("0"))
          then
            val reset = headers
              .get("x-ratelimit-reset")
              .filter(_.matches("[0-9]{1,12}"))
              .map(value => s" Reset Unix timestamp: $value.")
              .getOrElse("")
            Left(s"GitHub rate limit reached; retry later.$reset")
          else if response.status == 403 then
            Left("GitHub access forbidden or secondary rate limit reached (HTTP 403).")
          else if response.status == 404 then Left("GitHub repository was not found (HTTP 404).")
          else if response.status != 200 then
            Left(s"GitHub returned HTTP ${response.status}; no data loaded.")
          else if response.body.length > MaxResponseBytes then
            Left("GitHub response exceeds the 4 MiB limit.")
          else
            Try {
              val entries = ujson.read(response.body).arr.toVector
              require(entries.size <= 30)
              val retrievedAt = Instant.now().toString
              entries.filterNot(_.obj.contains("pull_request")).map { entry =>
                val number = entry("number").num
                require(number > 0 && number <= Int.MaxValue && number == number.toInt.toDouble)
                val input = TriageInput(
                  number.toInt.toString,
                  entry("title").str,
                  if entry("body") == ujson.Null then "" else entry("body").str
                )
                Issue(
                  input,
                  entry("html_url").str,
                  entry("state").str,
                  entry("labels").arr.toVector.map(_("name").str),
                  retrievedAt,
                  Issue.hashInput(input)
                )
              }
            }.toEither.left
              .map(_ => "GitHub returned an invalid or oversized issue page.")
              .flatMap { issues =>
                if issues.exists(_.state != state) then
                  Left("GitHub returned an issue with an unexpected state.")
                else DataValidation.issues(issues).map(_ => issues)
              }
        }
      (1 to pages)
        .foldLeft[Either[String, Vector[Issue]]](Right(Vector.empty)) { (loaded, number) =>
          loaded.flatMap(previous => page(number).map(previous ++ _))
        }
        .flatMap(issues => DataValidation.issues(issues).map(_ => issues))

private object DataValidation:
  def timestamp(value: String): Boolean = value.length <= 64 && Try(Instant.parse(value)).isSuccess

  def issues(values: Vector[Issue]): Either[String, Unit] =
    if values.size > 90 then Left("Issue data exceeds the 90-issue limit.")
    else if values.map(_.input.id).distinct.size != values.size then
      Left("Issue data contains duplicate IDs.")
    else
      values.foldLeft[Either[String, Unit]](Right(())) { (previous, issue) =>
        previous.flatMap { _ =>
          val id = issue.input.id
          if !id.matches("[1-9][0-9]{0,9}") || Try(id.toInt).toOption.forall(_ <= 0) then
            Left("Issue source ID must be a positive GitHub issue number.")
          else if issue.url != s"https://github.com/${GitHubSource.Repository}/issues/$id" then
            Left("Issue source URL does not match the fixed repository and ID.")
          else if !Set("open", "closed").contains(issue.state) || !timestamp(issue.retrievedAt) ||
            issue.input.title.trim.isEmpty || issue.labels.size > 100 ||
            issue.labels.distinct.size != issue.labels.size ||
            issue.labels.exists(label => label.trim.isEmpty || label.length > 100)
          then Left("Issue source fields are invalid.")
          else if issue.hash != Issue.hashInput(issue.input) then Left("Issue content hash does not match.")
          else
            Validation
              .validateInput(issue.input)
              .left
              .map(_ => "Issue title/body is invalid or exceeds 20000 characters; content was not truncated.")
        }
      }

  def replay(value: Replay, taxonomy: Taxonomy): Either[String, Unit] =
    if value.schemaVersion != 1 then Left("Replay schema version is unsupported.")
    else if value.taxonomy != taxonomy then Left("Replay taxonomy does not match the configured taxonomy.")
    else if value.promptVersion != Triage.PromptVersion then Left("Replay prompt version is unsupported.")
    else if !timestamp(value.capturedAt) then Left("Replay capture timestamp is invalid.")
    else if value.items.isEmpty || value.items.size > 10 then
      Left("Replay must contain 1 to 10 recorded results.")
    else
      for
        _ <- Validation.validateTaxonomy(taxonomy).left.map(_ => "Replay taxonomy is invalid.")
        _ <- issues(value.items.map(_.issue))
        _ <- value.items.foldLeft[Either[String, Unit]](Right(())) { (previous, item) =>
          previous.flatMap { _ =>
            val response = item.response
            if response.model.trim.isEmpty || response.model.length > 200 ||
              response.model.exists(_.isControl) ||
              response.usage.exists(u =>
                u.prompt < 0 || u.completion < 0 || u.total < 0 ||
                  u.prompt.toLong + u.completion != u.total.toLong
              )
            then Left("Replay model identity or token usage is invalid.")
            else
              Validation
                .validateResult(item.issue.input, taxonomy, response.result)
                .left
                .map(_ => "Replay classification or source evidence is invalid.")
                .map(_ => ())
          }
        }
      yield ()

private final case class IssueSnapshot(
    schemaVersion: Int,
    repository: String,
    issues: Vector[Issue]
) derives ReadWriter

class SnapshotStore(root: Path):
  private val base = root.toAbsolutePath.normalize()
  private val maxBytes = 8 * 1024 * 1024
  private val privateDirectory = PosixFilePermissions.fromString("rwx------")
  private val privateFile = PosixFilePermissions.fromString("rw-------")

  def saveIssues(issues: Vector[Issue]): Either[String, Unit] =
    DataValidation
      .issues(issues)
      .flatMap(_ => save("issues", write(IssueSnapshot(1, GitHubSource.Repository, issues))))

  def loadIssues(): Either[String, Vector[Issue]] =
    load("issues").flatMap { text =>
      Try {
        val snapshot = read[IssueSnapshot](text)
        if snapshot.schemaVersion != 1 || snapshot.repository != GitHubSource.Repository then
          Left("Saved issue snapshot schema or repository is unsupported.")
        else DataValidation.issues(snapshot.issues).map(_ => snapshot.issues)
      }.toEither.left
        .map(_ => "Saved issue snapshot is invalid JSON or schema.")
        .flatten
    }

  def saveReplay(replay: Replay): Either[String, Unit] =
    DataValidation.replay(replay, replay.taxonomy).flatMap(_ => save("replay", write(replay)))

  def loadReplay(taxonomy: Taxonomy): Either[String, Replay] =
    load("replay").flatMap { text =>
      Try {
        val replay = read[Replay](text)
        DataValidation.replay(replay, taxonomy).map(_ => replay)
      }.toEither.left
        .map(_ => "Saved replay is invalid JSON or schema.")
        .flatten
    }

  private def directory(kind: String, create: Boolean): Path =
    require(Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS), "Invalid storage root")
    Vector(base.resolve("fixtures"), base.resolve("fixtures").resolve(kind)).foreach { path =>
      if create && !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(privateDirectory))
      require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), "Invalid snapshot directory")
      Files.setPosixFilePermissions(path, privateDirectory)
    }
    base.resolve("fixtures").resolve(kind)

  private def save(kind: String, text: String): Either[String, Unit] =
    val bytes = text.getBytes(UTF_8)
    if bytes.length > maxBytes then Left("Snapshot exceeds the 8 MiB storage limit.")
    else
      Try {
        val folder = directory(kind, create = true)
        val target = folder.resolve("latest.json")
        require(!Files.isSymbolicLink(target), "Symbolic links are not allowed")
        val pending = folder.resolve(s".pending-${java.util.UUID.randomUUID()}.json")
        Files.createFile(pending, PosixFilePermissions.asFileAttribute(privateFile))
        try
          Using.resource(java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE)) { channel =>
            val buffer = ByteBuffer.wrap(bytes)
            while buffer.hasRemaining do channel.write(buffer)
            channel.force(true)
          }
          Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          ()
        finally Files.deleteIfExists(pending)
      }.toEither.left.map(_ =>
        s"Cannot atomically save fixtures/$kind/latest.json with private filesystem permissions."
      )

  private def load(kind: String): Either[String, String] =
    val target = base.resolve("fixtures").resolve(kind).resolve("latest.json")
    if !Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
      Left(s"Missing fixtures/$kind/latest.json; no automatic live fallback.")
    else
      Try {
        directory(kind, create = false)
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS), "Invalid snapshot file")
        require(Files.size(target) <= maxBytes, "Snapshot exceeds limit")
        Files.setPosixFilePermissions(target, privateFile)
        Using.resource(Files.newByteChannel(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
          channel =>
            val bytes = new ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            var count = channel.read(buffer)
            while count != -1 do
              require(bytes.size().toLong + count <= maxBytes, "Snapshot exceeds limit")
              bytes.write(buffer.array(), 0, count)
              buffer.clear()
              count = channel.read(buffer)
            UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray)).toString
        }
      }.toEither.left.map(_ =>
        s"Cannot read fixtures/$kind/latest.json: invalid file, permissions, encoding, or 8 MiB size limit."
      )
