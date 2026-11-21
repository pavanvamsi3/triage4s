package triage4s.demo

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import org.llm4s.error.RateLimitError
import org.llm4s.llmconnect.config.{ContextWindowResolver, OpenAIConfig}
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.llmconnect.model.{CompletionOptions, Conversation, ResponseFormat, UserMessage}
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.scalatest.funsuite.AnyFunSuite
import triage4s.*

/** What the published LLM4S OpenAI client does on the wire, against a local server that always answers 429:
  * the behaviour the dashboard's transport notice describes.
  */
class OpenAITransportSpec extends AnyFunSuite:
  private def withRateLimitedServer(test: (String, AtomicInteger) => Unit): Unit =
    val requests = new AtomicInteger()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange => {
        requests.incrementAndGet()
        exchange.getRequestBody.readAllBytes()
        val body = """{"error":{"message":"test rate limit","type":"rate_limit_error","code":"rate_limit"}}"""
          .getBytes(UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/json")
        // no waiting between the SDK's attempts, so the test is fast
        exchange.getResponseHeaders.set("retry-after-ms", "1")
        exchange.sendResponseHeaders(429, body.length)
        exchange.getResponseBody.write(body)
        exchange.close()
      }
    )
    server.start()
    try test(s"http://127.0.0.1:${server.getAddress.getPort}/v1", requests)
    finally server.stop(0)

  test("a rate-limited call is retried by the SDK at most twice, then reported as a rate-limit error"):
    withRateLimitedServer { (endpoint, requests) =>
      given ModelRegistryService = ModelRegistryService.default().toOption.get
      given ContextWindowResolver = ContextWindowResolver(summon[ModelRegistryService])
      val config =
        OpenAIConfig.fromValues("gpt-4o-mini", Secret("test-only-not-a-real-key"), endpoint).toOption.get
      val client = OpenAIClient(config).toOption.get
      try
        val outcome = client.complete(
          Conversation(Seq(UserMessage("Test-only JSON classification."))),
          CompletionOptions(
            maxTokens = Some(1200),
            responseFormat = Some(ResponseFormat.JsonSchema(Triage.schema(Taxonomy.copilotLens), "triage"))
          )
        )
        assert(outcome.left.toOption.exists(_.isInstanceOf[RateLimitError]))
        assert(requests.get() == 3)
      finally client.close()
    }
