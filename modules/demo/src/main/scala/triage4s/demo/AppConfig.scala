package triage4s.demo

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ContextWindowResolver, OpenAIConfig}
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.model.ModelRegistryService
import scala.util.Try

final class AppConfig(
    val port: Int,
    val model: String,
    val apiKey: Option[String],
    val githubToken: Option[String]
):
  override def toString: String = s"AppConfig(port=$port, model=$model, credentials=[redacted])"

object AppConfig:
  val TransportNotice =
    "No application or SDK retries. Connect timeout 10s; response/read/write timeouts 60s. " +
      "These are transport-phase limits, not an end-to-end deadline. Output capped at 1200 tokens."

  def load(environment: Map[String, String]): Either[String, AppConfig] =
    def optional(key: String) = environment.get(key).map(_.trim).filter(_.nonEmpty)
    val port = optional("TRIAGE_PORT").getOrElse("8080")
    val model = optional("TRIAGE_MODEL").getOrElse("gpt-4o-mini")
    for
      parsed <- port.toIntOption
        .filter(p => p >= 1024 && p <= 65535)
        .toRight("TRIAGE_PORT must be an integer from 1024 to 65535.")
      _ <- Either.cond(model.matches("[a-zA-Z0-9._:-]{1,100}"), (), "TRIAGE_MODEL has an invalid format.")
    yield new AppConfig(parsed, model, optional("OPENAI_API_KEY"), optional("GITHUB_TOKEN"))

  def configureTransport(): Unit =
    // LLM4S 0.4.1 does not expose its Azure SDK builder; configure before SDK initialization.
    System.setProperty("AZURE_REQUEST_RETRY_COUNT", "0")
    System.setProperty("AZURE_REQUEST_CONNECT_TIMEOUT", "10000")
    System.setProperty("AZURE_REQUEST_RESPONSE_TIMEOUT", "60000")
    System.setProperty("AZURE_REQUEST_READ_TIMEOUT", "60000")
    System.setProperty("AZURE_REQUEST_WRITE_TIMEOUT", "60000")

  def client(config: AppConfig): Either[String, LLMClient] =
    for
      key <- config.apiKey.toRight(
        "OPENAI_API_KEY is not configured. Set it locally and restart; do not paste keys into chat."
      )
      registry <- ModelRegistryService.default().left.map(_ => "Failed to load bundled LLM4S model metadata.")
      client <- Try {
        given ModelRegistryService = registry
        given ContextWindowResolver = ContextWindowResolver(registry)
        val provider = OpenAIConfig.fromValues(config.model, key, None, "https://api.openai.com/v1")
        OpenAIClient(provider)
      }.toEither.left
        .map(e => s"Could not configure OpenAI (${e.getClass.getSimpleName}).")
        .flatMap(_.left.map(_ => "LLM4S could not create the OpenAI client."))
    yield client
