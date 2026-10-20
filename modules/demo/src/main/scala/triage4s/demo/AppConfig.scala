package triage4s.demo

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.llmconnect.config.{ContextWindowResolver, OpenAIConfig, ProviderTimeouts}
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import scala.concurrent.duration.DurationInt
import scala.util.Try

final class AppConfig(
    val port: Int,
    val model: String,
    val apiKey: Option[String],
    val githubToken: Option[String],
    /** The LLM4S provider section to use (`TRIAGE_PROVIDER`); without one, OpenAI with `OPENAI_API_KEY`. */
    val providerSection: Option[String] = None,
    /** `TRIAGE_MODEL` as set, which replaces a provider section's own model; `model` adds the OpenAI default.
      */
    val modelOverride: Option[String] = None,
    /** `TRIAGE_REQUESTS_PER_MINUTE`: a local limit on provider calls, off when unset (#91). */
    val requestsPerMinute: Option[Int] = None,
    /** Where the dashboard listens (`TRIAGE_BIND_ADDRESS`): loopback, or all interfaces inside a container
      * (#101).
      */
    val bindAddress: String = AppConfig.Loopback,
    /** The classification taxonomy: `TRIAGE_TAXONOMY_FILE`, or the built-in one (#13). */
    val taxonomy: triage4s.Taxonomy = triage4s.Taxonomy.copilotLens
):
  override def toString: String =
    s"AppConfig(port=$port, model=$model, provider=${providerSection.getOrElse("openai")}, credentials=[redacted])"

/** The client the dashboard calls, with the provider and model it shows. */
final case class ConfiguredProvider(client: LLMClient, provider: String, model: String)

object AppConfig:
  val Loopback = "127.0.0.1"

  /** The addresses the dashboard may listen on: loopback, or every interface for a container (#101). */
  val BindAddresses: Set[String] = Set(Loopback, "0.0.0.0")

  /** The OpenAI endpoint the demo calls; always HTTPS, so the key never travels in plaintext. */
  val OpenAIBaseUrl = "https://api.openai.com/v1"

  /** How long one attempt of a classification call may take before it fails. */
  val RequestTimeout = 60.seconds

  val TransportNotice =
    "Transient provider failures (rate limit, 5xx, timeout) are retried up to 2 times with backoff - by the vendor SDK " +
      "for OpenAI and Anthropic, by LLM4S for the others. After 3 failed issues in a row the provider is paused for " +
      "30s. Each call, retries included, has 3 minutes; an OpenAI attempt 60s. Output capped at 1200 tokens."

  def load(environment: Map[String, String]): Either[String, AppConfig] =
    def optional(key: String) = environment.get(key).map(_.trim).filter(_.nonEmpty)
    val port = optional("TRIAGE_PORT").getOrElse("8080")
    val model = optional("TRIAGE_MODEL").getOrElse("gpt-4o-mini")
    for
      parsed <- port.toIntOption
        .filter(p => p >= 1024 && p <= 65535)
        .toRight("TRIAGE_PORT must be an integer from 1024 to 65535.")
      _ <- Either.cond(model.matches("[a-zA-Z0-9._:-]{1,100}"), (), "TRIAGE_MODEL has an invalid format.")
      rpm <- optional("TRIAGE_REQUESTS_PER_MINUTE") match
        case None        => Right(None)
        case Some(value) =>
          value.toIntOption
            .filter(n => n >= 1 && n <= 10000)
            .map(Some(_))
            .toRight("TRIAGE_REQUESTS_PER_MINUTE must be an integer from 1 to 10000.")
      section <- optional("TRIAGE_PROVIDER") match
        case Some(name) if !name.matches("[a-zA-Z0-9_-]{1,64}") =>
          Left("TRIAGE_PROVIDER has an invalid format.")
        case other => Right(other)
      bind = optional("TRIAGE_BIND_ADDRESS").getOrElse(Loopback)
      _ <- Either.cond(
        BindAddresses.contains(bind),
        (),
        "TRIAGE_BIND_ADDRESS must be 127.0.0.1, or 0.0.0.0 inside a container."
      )
      taxonomy <- TaxonomyFile.fromEnvironment(environment)
    yield new AppConfig(
      parsed,
      model,
      optional("OPENAI_API_KEY"),
      optional("GITHUB_TOKEN"),
      section,
      optional("TRIAGE_MODEL"),
      rpm,
      bind,
      taxonomy
    )

  /** The LLM4S provider configuration for `config`'s model and `key`: the OpenAI endpoint, with
    * [[RequestTimeout]].
    */
  def providerConfig(config: AppConfig, key: String)(using ContextWindowResolver): Result[OpenAIConfig] =
    OpenAIConfig
      .fromValues(config.model, key, None, OpenAIBaseUrl)
      .map(_.withTimeouts(ProviderTimeouts(request = Some(RequestTimeout))))

  /** The configured client: the LLM4S provider section `TRIAGE_PROVIDER` names (any provider module on the
    * classpath, configured in `application.conf`), or else OpenAI with `OPENAI_API_KEY` and `TRIAGE_MODEL`.
    */
  def configured(config: AppConfig): Either[String, ConfiguredProvider] =
    config.providerSection match
      case Some(section) => named(section, config.modelOverride)
      case None          => client(config).map(ConfiguredProvider(_, "openai", config.model))

  /** The client of LLM4S provider section `section`, with its model replaced by `model` when that is set. */
  private def named(section: String, model: Option[String]): Either[String, ConfiguredProvider] =
    for
      registry <- Llm4sConfig
        .modelRegistryService()
        .left
        .map(_ => "Failed to load bundled LLM4S model metadata.")
      provider <- Llm4sConfig.provider(section).left.map(e => s"Provider section '$section': ${e.message}")
      chosen = model.fold(provider)(provider.withModel)
      client <- Try(LLMConnect.getClient(chosen)(using registry)).toEither.left
        .map(e => s"Could not configure provider '$section' (${e.getClass.getSimpleName}).")
        .flatMap(_.left.map(e => s"LLM4S could not create the client for '$section': ${e.message}"))
    yield ConfiguredProvider(client, chosen.providerId.asString, chosen.model)

  def client(config: AppConfig): Either[String, LLMClient] =
    for
      key <- config.apiKey.toRight(
        "OPENAI_API_KEY is not configured. Set it locally and restart, or choose another provider with " +
          "TRIAGE_PROVIDER; do not paste keys into chat."
      )
      registry <- ModelRegistryService.default().left.map(_ => "Failed to load bundled LLM4S model metadata.")
      client <- Try {
        given ModelRegistryService = registry
        given ContextWindowResolver = ContextWindowResolver(registry)
        providerConfig(config, key).flatMap(OpenAIClient(_))
      }.toEither.left
        .map(e => s"Could not configure OpenAI (${e.getClass.getSimpleName}).")
        .flatMap(_.left.map(_ => "LLM4S could not create the OpenAI client."))
    yield client
