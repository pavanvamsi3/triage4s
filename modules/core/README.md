# Triage4s Core

`triage4s-core` is the reusable Scala library for evidence-backed AI issue triage. It validates the input taxonomy and the model response, requires exact source excerpts for classifications, and supports explicit abstention when the issue is ambiguous.

The [`demo` module](../demo) is a separate local dashboard and GitHub adapter. It loads public issues, owns the application configuration, and exposes the HTTP UI. The core library does not call GitHub, start a server, read environment variables, or configure a provider for you.

## Installation for local development

The artifact is not published to Maven Central. From the repository root, publish it to your local Ivy/Maven cache:

```bash
sbt "core/publishLocal"
```

Then use the current Scala 3 coordinates in another local build:

```scala
libraryDependencies += "io.github.triage4s" %% "triage4s-core" % "0.1.0-SNAPSHOT"
```

The repository's normal build uses Scala 3.7.1 and LLM4S 0.4.1. No provider configuration or additional dependency is required by this module.

## Basic usage

Configure and inject an LLM4S `LLMClient` at your application's edge. The client remains owned by the caller:

```scala
import org.llm4s.llmconnect.LLMClient
import triage4s.*

def classify(client: LLMClient): Either[TriageError, TriageResponse] =
  val taxonomy = Taxonomy(
    version = "my-project-v1",
    types = Vector(
      Category("bug", "Existing behavior is broken."),
      Category("enhancement", "A new capability is requested.")
    ),
    components = Vector(
      Category("cli", "Command-line arguments and startup."),
      Category("storage", "Persistence and database access.")
    )
  )

  new Triage(client).triage(
    TriageInput(
      id = "example-1",
      title = "CLI crashes",
      body = "The launcher crashes on startup."
    ),
    taxonomy
  )
```

`Triage.triage` is a blocking call. In a server, run it on an executor intended for blocking work. The caller owns the injected `LLMClient` and is responsible for closing it when the application is finished with it. The core library does not create or close the client behind your back.

If the selected provider does not support JSON Schema, explicitly opt into JSON mode:

```scala
new Triage(client, useJsonSchema = false).triage(input, taxonomy)
```

Local validation is still applied in JSON mode; there is no automatic format fallback.

## Result contract

The result is an `Either[TriageError, TriageResponse]`:

- `Right(TriageResponse(...))` means the provider returned a response that passed local validation. A successful response may still abstain: `result.issueType` and/or `result.component` can be `None`, with `result.needsReview = true` and an explanation in `result.reviewReasons`.
- `Left(TriageError)` means the operation did not produce a valid response. This includes invalid input or taxonomy, malformed or fabricated model output, and provider failures. Provider exception details are intentionally sanitized.

On a successful response, inspect `result.issueType`, `result.component`, `result.rationale`, `result.evidence`, `result.needsReview`, and `result.reviewReasons`. Evidence quotes must be exact substrings of the supplied title or body; evidence proves attribution, not classification certainty.

For the full contract and validation rules, see the root README's [result contract](../../README.md#result-contract) section.

## Testing

Run the core test suite from the repository root:

```bash
sbt core/test
```

The tests use deterministic fake `LLMClient` implementations, so they do not require API keys, a live provider, Docker, or external services. They cover valid classifications, partial and full abstentions, invalid output, provider failures, input limits, JSON mode, and the blocking one-call behavior.

For broader project checks, see the root README's [development commands](../../README.md#development) and [limits and reliability](../../README.md#limits-and-reliability) sections.
