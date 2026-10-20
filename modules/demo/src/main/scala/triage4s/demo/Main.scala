package triage4s.demo

import triage4s.*
import java.nio.file.Path
import scala.util.{Try, Using}
import org.slf4j.LoggerFactory

object Main extends cask.MainRoutes:
  private val logger = LoggerFactory.getLogger(getClass)
  private val config = AppConfig
    .load(sys.env)
    .fold(
      error => { logger.error(error); throw new IllegalArgumentException(error) },
      identity
    )
  // resilient provider calls (#91), and a count of the attempts each issue took
  private val (provider, attempts) =
    AppConfig.configured(config).map(Reliability.wrap(_, config.requestsPerMinute)) match
      case Right((p, counter)) => (Right(p), () => counter.attempts)
      case Left(error)         => (Left(error), () => 0L)
  provider.left.foreach(logger.warn(_))
  private val github = new GitHubSource(config.githubToken, repository = config.repository)
  private val service = new RunService(
    github.load,
    new SnapshotStore(Path.of("."), config.repository),
    () => provider.map(p => new Triage(p.client)),
    Taxonomy.copilotLens,
    attempts = attempts
  )
  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    service.close()
    provider.foreach(_.client.close())
  }))

  // loopback by default; 0.0.0.0 only inside a container, whose port is published to the host's loopback (#101).
  // Either way the same-origin guard below accepts only a 127.0.0.1 or localhost Host header.
  override def host: String = config.bindAddress
  override def port: Int = config.port
  override def debugMode: Boolean = false

  override def main(args: Array[String]): Unit =
    val server = io.undertow.Undertow
      .builder()
      .addHttpListener(port, host)
      .setIoThreads(2)
      .setWorkerThreads(8)
      .setServerOption(io.undertow.UndertowOptions.MAX_ENTITY_SIZE, java.lang.Long.valueOf(4096))
      .setServerOption(io.undertow.UndertowOptions.IDLE_TIMEOUT, Integer.valueOf(60000))
      .setHandler(defaultHandler)
      .build()
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.stop()))
    server.start()
    val model = provider.fold(_ => "not configured", p => s"${p.provider} / ${p.model}")
    if host != AppConfig.Loopback then
      logger.warn(
        s"Listening on all interfaces ($host). Publish the port to loopback only, e.g. docker run -p 127.0.0.1:$port:$port."
      )
    println(s"Triage4s: http://${AppConfig.Loopback}:$port (GitHub read-only; model: $model)")

  private def json(value: ujson.Value, status: Int = 200): cask.Response[String] =
    cask.Response(
      value.render(),
      status,
      Seq(
        "Content-Type" -> "application/json; charset=utf-8",
        "Cache-Control" -> "no-store",
        "X-Content-Type-Options" -> "nosniff"
      )
    )

  private def error(message: String, status: Int = 400): cask.Response[String] =
    json(ujson.Obj("error" -> message), status)

  private def guarded(request: cask.Request)(action: => cask.Response[String]): cask.Response[String] =
    val allowedHosts = Set(s"127.0.0.1:$port", s"localhost:$port")
    val requestHost = request.exchange.getRequestHeaders.getFirst("Host")
    val origin = Option(request.exchange.getRequestHeaders.getFirst("Origin"))
    val fetchSite = Option(request.exchange.getRequestHeaders.getFirst("Sec-Fetch-Site"))
    if !allowedHosts.contains(requestHost) || origin.exists(o =>
        !allowedHosts.exists(h => o == s"http://$h")
      ) ||
      fetchSite.contains("cross-site")
    then error("Only same-origin local requests are allowed.", 403)
    else action

  private def post(
      request: cask.Request
  )(action: ujson.Value => cask.Response[String]): cask.Response[String] =
    guarded(request) {
      val contentType = Option(request.exchange.getRequestHeaders.getFirst("Content-Type")).getOrElse("")
      if contentType.takeWhile(_ != ';').trim != "application/json" then error("Use application/json.", 415)
      else
        request.exchange.setMaxEntitySize(4096)
        Try(ujson.read(request.text())).toEither match
          case Left(_)                 => error("Request must be a JSON object no larger than 4096 bytes.")
          case Right(value: ujson.Obj) => action(value)
          case Right(_)                => error("Request must be a JSON object.")
    }

  private def asset(request: cask.Request, name: String, mime: String): cask.Response[String] =
    guarded(request) {
      val loaded = Option(getClass.getResourceAsStream(s"/web/$name"))
        .toRight("Web asset is missing.")
        .flatMap(stream =>
          Using(stream)(s =>
            new String(s.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          ).toEither.left.map(_ => "Web asset could not be read.")
        )
      loaded.fold(
        message => error(message, 500),
        text =>
          cask.Response(
            text,
            200,
            Seq(
              "Content-Type" -> mime,
              "X-Content-Type-Options" -> "nosniff",
              "Content-Security-Policy" -> "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'",
              "Cache-Control" -> "no-store"
            )
          )
      )
    }

  @cask.get("/")
  def index(request: cask.Request): cask.Response[String] =
    asset(request, "index.html", "text/html; charset=utf-8")
  @cask.get("/style.css")
  def style(request: cask.Request): cask.Response[String] =
    asset(request, "style.css", "text/css; charset=utf-8")
  @cask.get("/app.js")
  def script(request: cask.Request): cask.Response[String] =
    asset(request, "app.js", "text/javascript; charset=utf-8")

  @cask.get("/api/config")
  def settings(request: cask.Request): cask.Response[String] = guarded(request) {
    json(
      ujson.Obj(
        "repository" -> config.repository,
        "model" -> provider.fold(_ => config.model, _.model),
        "provider" -> provider.fold(_ => config.providerSection.getOrElse("openai"), _.provider),
        "configured" -> provider.isRight,
        "taxonomy" -> upickle.default.writeJs(service.taxonomy),
        "limits" -> ujson.Obj("maxIssues" -> 10),
        "transportNotice" -> AppConfig.TransportNotice
      )
    )
  }

  @cask.get("/api/issues")
  def issues(
      request: cask.Request,
      mode: String = "live",
      state: String = "open",
      pages: Int = 1
  ): cask.Response[String] =
    guarded(request) { service.load(mode, state, pages).fold(error(_), json(_)) }

  @cask.get("/api/run")
  def runStatus(request: cask.Request): cask.Response[String] = guarded(request) { json(service.currentRun) }

  @cask.post("/api/run")
  def start(request: cask.Request): cask.Response[String] = post(request) { body =>
    val selection = Try {
      require(body.obj.keySet.toSet.subsetOf(Set("ids", "consent")))
      val ids = body("ids").arr.toVector.map(_.str)
      val consent = body.obj.get("consent").exists(_.bool)
      (ids, consent)
    }.toEither.left.map(_ => "Expected {ids: [string], consent: boolean}.")
    selection.flatMap { case (ids, consent) => service.start(ids, consent) }.fold(error(_), json(_))
  }

  @cask.post("/api/snapshot")
  def snapshot(request: cask.Request): cask.Response[String] = post(request) { _ =>
    service.saveSnapshot().fold(error(_), message => json(ujson.Obj("message" -> message)))
  }

  @cask.post("/api/record")
  def record(request: cask.Request): cask.Response[String] = post(request) { _ =>
    service.record().fold(error(_), message => json(ujson.Obj("message" -> message)))
  }

  initialize()
