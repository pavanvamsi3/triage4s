package io.github.pavanvamsi3.triage4s

import io.github.pavanvamsi3.triage4s.Triage
import io.github.pavanvamsi3.triage4s.TriageInput
import io.github.pavanvamsi3.triage4s.TriageResponse
import io.github.pavanvamsi3.triage4s.TriageSettings
import io.github.pavanvamsi3.triage4s.categories.*
import io.github.pavanvamsi3.triage4s.components.*
import upickle.default.*

fun main() {
    // A sample issue — same shape the project uses
    val issue = TriageInput(
        id = "1234567",
        title = "Sample: quickstart test issue",
        body = """Login flow broken after recent deploy.
                  |Steps to reproduce:
                  |1. Clone the repo
                  |2. Run `make setup`
                  |3. Try to log in
                  |4. Observe auth error
                  |"""
        .trim()
    )

    // A minimal taxonomy — types + components the project already ships
    val taxonomy = Taxonomy(
        version = "triage-v1",
        types = emptyVector(),
        components = emptyVector()
    )

    // Build the triage client using the default Anthropic provider
    // (any LLM provider llm4s-core supports will work)
    val triage = Triage(
        client = ???,  // LLMClient construction depends on your API key setup
        useJsonSchema = true,
        maxAttempts = 1,
        settings = TriageSettings.default
    )

    // Run triage
    val result: Either[TriageError, TriageResponse] = triage.triage(issue, taxonomy)

    // Print the result
    when (result) {
        is Right -> println("Classification: ${result.get.issueType} / ${result.get.component}")
        is Left -> println("Error: ${result.left.detail}")
    }
}

/* Key notes:
- ??? is where the LLM client goes — if you have an API key for any llm4s-supported provider (OpenAI, Anthropic, Claude, Gemini, Ollama), you construct it per the llm4s patterns. If you don't have a key, the project is designed to demo without one (see the existing sbt demo/run UI).
- All the case classes (TriageInput, Taxonomy, TriageResponse, etc.) use derives ReadWriter which maps directly to upickle JSON codecs — Kotlin can read them fine thanks to JVM interop.
- The emptyVector() calls are needed because Kotlin's emptyArray() / emptyList() don't automatically widen to Vector without the explicit call. */