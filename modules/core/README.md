# Triage4s Core

`triage4s-core` is the reusable Scala library for evidence-backed AI issue triage. It validates the input taxonomy and the model response, requires exact source excerpts for classifications, and supports explicit abstention when the issue is ambiguous.

The [`demo` module](../demo) is a separate local dashboard and GitHub adapter. It loads public issues, owns the application configuration, and exposes the HTTP UI. The core library does not call GitHub, start a server, read environment variables, or configure a provider for you.

## Installation for local development

Published to Maven Central from the first release (see [RELEASING.md](../../RELEASING.md)):

```scala
libraryDependencies += "io.github.pavanvamsi3" %% "triage4s-core" % "<version>"
```

To use unreleased changes, run `sbt "core/publishLocal"` from the repository root and use the `-SNAPSHOT` version it
prints.

The repository's normal build uses Scala 3.7.1 and LLM4S 0.5.1. This module depends on `llm4s-core` only: since LLM4S 0.5.0 provider clients live in their own modules, so your application adds the one it uses, for example `"org.llm4s" %% "llm4s-openai" % "0.5.1"` for `OpenAIClient`.

## Basic usage

Configure and inject an LLM4S `LLMClient` at your application's edge. The client remains owned by the caller (see the root README's [Using the library](../../README.md#using-the-library) for the full walkthrough):

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

By default each issue makes exactly one completion call, and output that fails validation is returned as `InvalidOutput`. To let a fresh sample replace an invalid one, opt into extra attempts (1 to `Triage.MaxAttempts`; only `InvalidOutput` is retried, never provider failures):

```scala
new Triage(client, maxAttempts = 2).triage(input, taxonomy)
```

Each call uses a 1,200-token completion budget at temperature 0. A model that needs a larger budget, or a different temperature, takes `TriageSettings` (`maxTokens` 1 to 32,000, `temperature` 0 to 2; other values are rejected when the settings are built):

```scala
new Triage(client, settings = TriageSettings(maxTokens = 4000)).triage(input, taxonomy)
```

To classify many issues with one taxonomy, `triageAll` checks the taxonomy and the uniqueness of the input IDs once, then classifies each input in order. Each issue keeps its own `Either`, so one invalid issue or model reply does not stop the batch:

```scala
new Triage(client).triageAll(inputs, taxonomy) // Either[TriageError, Vector[(id, Either[TriageError, TriageResponse])]]
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
