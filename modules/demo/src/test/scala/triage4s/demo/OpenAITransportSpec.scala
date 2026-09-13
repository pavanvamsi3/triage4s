package triage4s.demo

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import org.llm4s.llmconnect.config.{ContextWindowResolver, OpenAIConfig}
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.llmconnect.model.{CompletionOptions, Conversation, ResponseFormat, UserMessage}
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import triage4s.*

class OpenAITransportSpec extends AnyFunSuite:
  private def withRateLimitedServer(test: (String, AtomicInteger) => Unit): Unit =
    AppConfig.configureTransport()
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
        exchange.sendResponseHeaders(429, body.length)
        exchange.getResponseBody.write(body)
        exchange.close()
      }
    )
    server.start()
    try test(s"http://127.0.0.1:${server.getAddress.getPort}/v1", requests)
    finally server.stop(0)

  test("the published client rejects plaintext endpoints before sending a key"):
    withRateLimitedServer { (endpoint, requests) =>
      given ModelRegistryService = ModelRegistryService.default().toOption.get
      given ContextWindowResolver = ContextWindowResolver(summon[ModelRegistryService])
      val config = OpenAIConfig.fromValues(
        "gpt-4o-mini",
        "test-only-not-a-real-key",
        None,
        endpoint
      )
      val client = OpenAIClient(config).toOption.get
      try
        val outcome = client.complete(
          Conversation(Seq(UserMessage("Test-only JSON classification."))),
          CompletionOptions(
            maxTokens = Some(1200),
            responseFormat = Some(ResponseFormat.JsonSchema(Triage.schema(Taxonomy.copilotLens), "triage"))
          )
        )
        assert(outcome.isLeft)
        assert(requests.get() == 0)
        assert(outcome.left.toOption.exists(_.message.contains("HTTPS")))
      finally client.close()
    }

  test("the configured SDK retry policy sends exactly one request for a local 429"):
    withRateLimitedServer { (endpoint, requests) =>
      val pipeline = new com.azure.core.http.HttpPipelineBuilder()
        .policies(new com.azure.core.http.policy.RetryPolicy())
        .build()
      val response = pipeline.sendSync(
        new com.azure.core.http.HttpRequest(com.azure.core.http.HttpMethod.GET, endpoint),
        com.azure.core.util.Context.NONE
      )
      try
        assert(response.getStatusCode == 429)
        assert(requests.get() == 1)
      finally response.close()
    }
