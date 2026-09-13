package triage4s.demo

import org.scalatest.funsuite.AnyFunSuite

class AppConfigSpec extends AnyFunSuite:
  test("defaults work without credentials and never print configured keys"):
    val empty = AppConfig.load(Map.empty).toOption.get
    assert(empty.port == 8080)
    assert(empty.model == "gpt-4o-mini")
    assert(AppConfig.client(empty).isLeft)
    val configured = AppConfig.load(Map("OPENAI_API_KEY" -> "test-secret")).toOption.get
    assert(!configured.toString.contains("test-secret"))

  test("invalid ports and model names are explicit errors"):
    Vector("0", "80", "65536", "oops").foreach { port =>
      assert(AppConfig.load(Map("TRIAGE_PORT" -> port)).isLeft)
    }
    assert(AppConfig.load(Map("TRIAGE_MODEL" -> "../bad model")).isLeft)

  test("pinned SDK uses zero retry attempts and finite configured phase timeouts"):
    AppConfig.configureTransport()
    val azure = com.azure.core.util.Configuration.getGlobalConfiguration
    assert(azure.get("AZURE_REQUEST_RETRY_COUNT") == "0")
    assert(azure.get("AZURE_REQUEST_CONNECT_TIMEOUT") == "10000")
    assert(azure.get("AZURE_REQUEST_RESPONSE_TIMEOUT") == "60000")
    assert(azure.get("AZURE_REQUEST_READ_TIMEOUT") == "60000")
    assert(azure.get("AZURE_REQUEST_WRITE_TIMEOUT") == "60000")
    assert(new com.azure.core.http.policy.ExponentialBackoff().getMaxRetries == 0)
