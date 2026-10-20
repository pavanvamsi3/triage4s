# Triage4s

A Scala library for evidence-backed AI triage, with a local dashboard for real GitHub issues.

**LLM4S connects to the model. Triage4s defines the triage rules and validates the answer.**
The demo uses public issues from [pavanvamsi3/copilot-lens](https://github.com/pavanvamsi3/copilot-lens)
and never writes to GitHub.

Triage4s is part of the [LLM4S Foundation](https://llm4s.org/foundation/) and is built on
[LLM4S](https://github.com/llm4s/llm4s). See [Maintainers](#maintainers) and
[Community](#community) to meet the team.

## What it does

- Suggests an issue **type** and **component** from a configurable taxonomy.
- Requires exact source excerpts for every suggested classification.
- Allows partial or complete abstention, with explicit human-review reasons.
- Distinguishes classification uncertainty from provider failures and malformed output.
- Shows existing GitHub labels separately; they are not fed to the classifier or treated as truth.
- Provides live, saved-input, and recorded-result modes without silent fallbacks.

No priority scores, numerical confidence, automatic labels, agent loops, embeddings, database,
or repository-code access. Evidence proves that a quote exists, not that the classification is correct.

## Quick start

Requirements: **JDK 21** and **sbt**. The build pins Scala 3.7.1 and published LLM4S 0.5.1
(`llm4s-core` for the library, plus `llm4s-openai` for the demo's OpenAI client); no sibling LLM4S
checkout or `publishLocal` step is needed.

```bash
cd /path/to/triage4s
sbt test
sbt "demo/run"
```

Open **http://127.0.0.1:8080**. Without an OpenAI key, you can still load public GitHub issues,
inspect content, save/load input snapshots, and play an existing recording.
Model execution reports a configuration error rather than returning a fake result.

To enable classification, set these in the shell that launches the app:

```bash
export OPENAI_API_KEY="your-key"
export TRIAGE_MODEL="gpt-4o-mini"
sbt "demo/run"
```

Do not paste keys into chat, commit them, or put them in browser code. The application does **not**
automatically load `.env`; `.env.example` documents the settings only. Restart after changing configuration.

Optional settings:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TRIAGE_PORT` | `8080` | Loopback HTTP port, 1024-65535 |
| `TRIAGE_MODEL` | `gpt-4o-mini` | OpenAI model; must support the requested structured-output schema |
| `GITHUB_TOKEN` | unset | Optional authentication for public GitHub GET requests |
| `TRIAGE_TAXONOMY_FILE` | unset | A local JSON file with your own issue types and components (see below) |
| `TRIAGE_BIND_ADDRESS` | `127.0.0.1` | Where the dashboard listens: loopback, or `0.0.0.0` inside a container |
| `TRIAGE_PROVIDER` | unset | An LLM4S provider section to use instead of OpenAI: `openai`, `anthropic`, `gemini` or `ollama` (see below) |
| `TRIAGE_REQUESTS_PER_MINUTE` | unset | A local limit on model calls (1-10000), for a tight provider quota |

On macOS with Homebrew's keg-only JDK:

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

### Choosing a model provider

The demo calls OpenAI by default. To use another provider, set `TRIAGE_PROVIDER` to one of the LLM4S provider
sections in `modules/demo/src/main/resources/application.conf`, with that vendor's usual key variable:

```bash
export TRIAGE_PROVIDER=anthropic ANTHROPIC_API_KEY="your-key"   # Claude
export TRIAGE_PROVIDER=gemini GOOGLE_API_KEY="your-key"         # Gemini
export TRIAGE_PROVIDER=ollama                                    # a local Ollama model, no key
sbt "demo/run"
```

Each section has a default model; `TRIAGE_MODEL` replaces it. For any other LLM4S provider - Azure OpenAI, Bedrock,
an OpenAI-compatible server - add a section of your own in a file passed with `-Dconfig.file=...` (see the
[LLM4S providers guide](https://github.com/llm4s/llm4s/blob/main/docs/guide/providers.md)), and add its LLM4S module
to the demo's dependencies if it is not one of the four above. How strictly a provider follows the triage JSON
schema varies (OpenAI enforces it; Anthropic receives it as a prompt instruction), so Triage4s's own validation
decides what is accepted, whichever provider answered.

### In a container

```bash
docker run --rm -e OPENAI_API_KEY -p 127.0.0.1:8080:8080 ghcr.io/pavanvamsi3/triage4s:main
# or build it yourself: docker build -t triage4s . && docker run --rm -e OPENAI_API_KEY -p 127.0.0.1:8080:8080 triage4s
```

The image runs as a non-root user and listens on all interfaces inside the container (`TRIAGE_BIND_ADDRESS=0.0.0.0`).
Publish the port to the host's loopback only (`-p 127.0.0.1:...`) and on the same number as `TRIAGE_PORT`: the dashboard
accepts only requests addressed to `127.0.0.1:<port>` or `localhost:<port>`, which also guards against DNS rebinding.
Pass keys with `-e`, never in the image. Snapshots and recordings are written under `/app/fixtures`; mount a volume there
to keep them.

### Your own taxonomy

The dashboard classifies against a built-in taxonomy for copilot-lens. To use your own issue types and components, point
`TRIAGE_TAXONOMY_FILE` at a JSON file; [`examples/taxonomy.json`](examples/taxonomy.json) is a complete example:

```json
{
  "version": "example-webshop-v1",
  "types": [{ "id": "bug", "description": "Something that should work is broken." }],
  "components": [{ "id": "checkout", "description": "The cart, payment and order confirmation." }]
}
```

The file is read once at startup, must be at most 64 KiB, and is checked with the same rules as every taxonomy: a
non-blank `version`; 1 to 30 categories per group; lowercase IDs (`[a-z][a-z0-9-]*`, up to 50 characters) without
duplicates; descriptions of 1 to 500 characters. Descriptions are what the model reads, so make them specific. An
invalid or missing file stops the server with a message naming it; there is no fallback to the built-in taxonomy. The
same taxonomy is used for classification, shown in the dashboard, and stored in recordings; a recording made with
another taxonomy is refused rather than remapped, and the `Evaluation` command reads `TRIAGE_TAXONOMY_FILE` too.

## Demo walkthrough

1. Select **Live GitHub**, open or closed issues, and 1-3 pages. Click **Load issues**.
2. Open an issue and inspect its title/body. Existing labels remain visibly separate from AI suggestions.
3. Select at most 10 issues. Confirm that you reviewed the public text for sensitive information
   and consent to sending it to OpenAI. Only then run triage.
4. Watch per-issue progress and inspect the type, component, evidence, or review reasons.
5. Failed issues do not stop later ones. Retry explicitly after the run finishes.
6. Save an input snapshot for repeatable loading. After a fully successful run, record results.
7. Switch to **Recorded replay** and load the recording for a network-free presentation.

| Mode | GitHub calls | OpenAI calls | Meaning |
| --- | --- | --- | --- |
| Live GitHub | On explicit load | On explicit triage | Current public source data |
| Saved snapshot | None | On explicit triage | Previously saved inputs, **not** saved answers |
| Recorded replay | None | None | Previously captured, validated answers |

Replay displays capture metadata and is never presented as a fresh model response.
No recording is bundled: create one from a genuine successful run. Missing or incompatible
recordings produce a visible error. Unit-test fixtures are synthetic and are not demo recordings.

## Using the library

The reusable code lives in `modules/core`; the GitHub adapter and server live in `modules/demo`.
This project has not been published to Maven Central. For local experimentation:

```bash
sbt "core/publishLocal"
```

Then add to another Scala 3 build:

```scala
libraryDependencies += "io.github.triage4s" %% "triage4s-core" % "0.1.0-SNAPSHOT"
```

Inject an LLM4S client configured at your application edge:

```scala
import org.llm4s.llmconnect.LLMClient
import triage4s.*

def classify(client: LLMClient): Either[TriageError, TriageResponse] = {
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
    TriageInput("example-1", "CLI crashes", "The launcher crashes on startup."),
    taxonomy
  )
}
```

The caller owns the client and closes it when finished. `triage` is blocking; use an appropriate
blocking executor in a server. The demo does this on a dedicated single-thread worker.
For an explicitly selected provider without schema support, `new Triage(client, useJsonSchema = false)`
requests JSON mode instead. Local validation remains mandatory; there is no automatic format fallback.

### Result contract

`TriageResponse` contains:

- `result.issueType` and `result.component`: optional allowed category identifiers.
- `result.rationale`: concise explanation.
- `result.evidence`: source field (`title`/`body`), classification target, and exact quote.
- `result.needsReview` and `result.reviewReasons`: consistent review status.
- `model`: model identity reported by the provider.
- `usage`: actual provider token counts, when available; never invented cost estimates.

Unset classifications require human review. Unknown identifiers, invalid JSON, fabricated evidence,
and inconsistent result fields return `Left(TriageError.InvalidOutput(...))`, not successful abstentions.
The demo's internal JSON endpoints use uPickle's `Option` encoding (`[]` or `[value]`); they are not a
versioned public API.

### From Java

`triage4s-core` includes a Java API (`triage4s.javaapi`) with Java types only, so a Java service needs no Scala
knowledge. No call throws: a failure is a `JResult` with a `JErrorKind` (`INVALID_INPUT`, `INVALID_TAXONOMY`,
`INVALID_OUTPUT`, `PROVIDER_FAILURE`, `CONFIGURATION`).

```java
import triage4s.javaapi.*;

// the client of an LLM4S provider section in your application.conf; add its module, e.g. llm4s-openai
try (JTriage triage = JTriage.fromConfiguration("openai-main").get()) {
    JResult<JTriageResponse> result = triage.triage("42", "CLI crashes", "The launcher crashes on startup.");
    if (result.isSuccess()) {
        JTriageResponse suggestion = result.get();
        System.out.println(suggestion.issueType().orElse("abstained") + " / " + suggestion.component().orElse("abstained"));
        suggestion.evidence().forEach(e -> System.out.println(e.target() + ": \"" + e.quote() + "\""));
    } else {
        System.err.println(result.errorKind().orElseThrow() + ": " + result.error().orElseThrow());
    }
}
```

`JTriage.of(client)` uses an LLM4S `LLMClient` you already have, and `JTaxonomy.builder("v1").issueType(...).component(...)`
builds your own taxonomy.

## Command line

`triage4s classify` suggests a type and component for one issue from the terminal or a script. It is read-only, like
the dashboard.

```bash
sbt "cli/run classify --file issue.json"                          # {"id": "...", "title": "...", "body": "..."}
sbt "cli/run classify --repo pavanvamsi3/copilot-lens --issue 12"  # a public GitHub issue
sbt "cli/run classify --file issue.json --provider ollama --json"  # another LLM4S provider section; JSON output
```

`--provider` names an LLM4S provider section (`openai` by default; `anthropic`, `gemini` and `ollama` are included,
see `modules/cli/src/main/resources/application.conf`), `--model` replaces its model, and `--taxonomy` reads a taxonomy
from a JSON file. The exit code says what happened: **0** a suggestion was made, **2** it needs human review, **1** an
error. Installing it as a `triage4s` command (Coursier `cs install`) follows once the artifacts are published (#96).

## Limits and reliability

- Up to **10 distinct selected issues**, one active run, one issue at a time.
- Up to **20,000 combined title/body characters**; oversized input fails rather than being silently truncated.
- Up to **1,200 output tokens**; truncated/invalid JSON is an error, not automatically repaired.
- GitHub loads **30 entries per page, at most 3 pages**. Pull requests are filtered out, so the issue
  count may be lower. The UI warns that results are capped; this is not an exhaustive repository scan.
- Provider calls go through LLM4S's `ReliableClient`. A transient failure (rate limit, 5xx, connection error) is
  retried up to **2 times** with backoff: by the vendor SDK for OpenAI and Anthropic, which already retries (so
  LLM4S adds none there), and by LLM4S for the other providers. Authentication and validation errors are never
  retried, and malformed model output is a validation result, not a provider error. An issue that needed more than
  one attempt says so in its result.
- After **3 failed calls in a row** the provider is paused for 30 seconds (a circuit breaker), so a batch stops
  sending requests to a provider that is down; the remaining issues fail fast and can be retried after the run.
- Each call has **3 minutes**, retries included; an OpenAI attempt times out after **60 seconds** (`ProviderTimeouts`).
- `TRIAGE_REQUESTS_PER_MINUTE` adds a local rate limit, off by default.
- The reusable library does not change any transport setting; configure your injected client yourself.
- Runs are in memory and disappear on restart. Saving a snapshot or recording replaces the previous
  local `latest.json`; it is not an archive.
- The demo calls OpenAI at `https://api.openai.com/v1` only, so the key is never sent in plaintext.

## Data, privacy, and local files

Inputs are untrusted text, never instructions to execute tools. The model has no tools.
The dashboard escapes source and model text and restricts requests to the local origin.
API keys stay on the backend. Raw provider exchanges are disabled; raw SDK/provider exception logging
is suppressed because it can include request data. The UI receives sanitized failures.

Local fixtures are stored at:

```text
fixtures/issues/latest.json
fixtures/replay/latest.json
```

These files are ignored by Git. They retain source links, timestamps, and input hashes.
Replay verifies its schema, taxonomy/prompt versions, source hashes, and evidence before use.

**Public does not mean safe to send or licensed for redistribution.** Inspect issue text for accidental
secrets or personal information before submitting it to OpenAI. Keep downloaded snapshots local unless
you have confirmed the necessary rights. The committed source manifest contains identifiers only.
Do not copy Copilot Lens's local assistant conversation data into this demo.

This is a loopback-only hackathon application, not an authenticated, hardened public service.
Do not expose it through a public tunnel or bind it to all network interfaces.
Choose a project license before publishing or distributing Triage4s.

## Evaluation

The evaluation command scores genuine recordings against **human-reviewed annotations**; it does not call
OpenAI or manufacture a benchmark. Existing GitHub labels are not ground truth.

1. Use the manifest to curate approximately 15 real issues. Inspect their actual content.
2. Reserve a development subset (for prompt/rule iteration) and a held-out subset (for final reporting).
   Keep each run within the 10-issue limit.
3. Before reading model answers, annotate accepted types/components, desired review status, input hashes,
   split, and reviewer name. Ambiguous examples can have multiple accepted outcomes.
4. Save annotations in `fixtures/evaluation/expected-local.json` (ignored by Git).
5. Run and record the matching subset, then evaluate it. Record the other subset separately if needed;
   only the latest recording is loaded by the command.

Annotation format is a JSON array, using uPickle's optional-value encoding:

```json
[
  {
    "id": "ISSUE_NUMBER",
    "inputHash": "COPY_HASH_FROM_SAVED_ISSUE",
    "acceptedTypes": [["bug"]],
    "acceptedComponents": [["cli"], []],
    "needsReview": true,
    "split": "heldout",
    "reviewedBy": "YOUR_NAME"
  }
]
```

This is a **format example**, not an annotation for any real issue. `[["bug"]]` accepts `Some("bug")`;
`[[]]` accepts abstention; `[["cli"], []]` accepts either CLI or abstention.

```bash
sbt "demo/runMain triage4s.demo.Evaluation fixtures/evaluation/expected-local.json heldout"
```

The report includes separate type/component accuracy, exact matches, review decisions, abstentions,
and coverage, with counts and denominators, plus `perCategory` precision and recall for every type and
component (null where a denominator is zero). `mismatches` lists each recorded example that fails a check (IDs,
category IDs and review decisions only, never issue text) and `missing` lists the IDs with no result. Missing results remain in accuracy and recall denominators and are not
silently discarded or mislabeled as known provider errors. A small hackathon set is not proof of production
reliability. Live model quality and genuine replay remain unverified until credentials, approved inputs,
and human annotations are provided.

## Development

```bash
sbt scalafmtAll
sbt test
sbt scalafmtCheckAll
node --check modules/demo/src/main/resources/web/app.js
```

Tests use deterministic fakes and temporary fixtures: no API keys, Docker, or external services are required.
They cover output validation, exact input limits, abstention, no retries, sequential runs, source limits,
snapshot/replay integrity, and evaluation denominators.

### Dependency updates

[Scala Steward](https://github.com/scala-steward-org/scala-steward) runs weekly (`.github/workflows/scala-steward.yml`)
and opens a pull request for each outdated dependency. All `org.llm4s` modules are updated together in one pull request
(`.scala-steward.conf`), so the build always uses one LLM4S version. Maintainers: set a `SCALA_STEWARD_TOKEN` secret so
that CI runs on those pull requests.

### Building against LLM4S main

A nightly workflow (`.github/workflows/llm4s-nightly.yml`) publishes LLM4S `main` locally and runs this build's tests
against it, so a breaking change there is caught within a day. When it fails, it opens (or comments on) an issue
labelled `llm4s-nightly`. To try it yourself, publish LLM4S locally and run
`sbt -Dllm4s.version=<that version> test`; without the property the build uses the released LLM4S version.

## Maintainers

- **Kannupriya Kalra** - [@kannupriyakalra](https://github.com/kannupriyakalra) ·
  [LinkedIn](https://www.linkedin.com/in/kannupriyakalra/) · [kannupriya@llm4s.org](mailto:kannupriya@llm4s.org)
- **Pavan Vamsi** - [@pavanvamsi3](https://github.com/pavanvamsi3) ·
  [LinkedIn](https://www.linkedin.com/in/pavanvamsi3/)

## Community

Triage4s is a project of the **[LLM4S Foundation](https://llm4s.org/foundation/)**, a global Scala and AI community that helps developers learn AI
engineering by contributing to real open-source projects such as [LLM4S](https://github.com/llm4s/llm4s), the AI
framework for Scala, and Triage4s, which is built on it.

### Contribute and grow: global internship opportunities

Contributing to Triage4s is a practical way into AI engineering, and through the
[LLM4S Foundation](https://llm4s.org/foundation/) it can open doors to global internship opportunities:

- **Build a public track record.** Every merged pull request is real, reviewed AI engineering work in Scala that you
  can point to in applications and interviews.
- **Learn from mentors.** Maintainers review your changes and help you grow, and you can meet them and experienced
  Scala developers at the weekly Dev Hours.
- **Get noticed for internships.** The foundation connects active contributors with global internship and career
  pathways, and mentors programmes such as [Google Summer of Code](https://llm4s.org/gsoc/). Consistent, high-quality
  contributions are the best way to be considered.
- **Grow from first issue to bigger work.** Start with a
  [good first issue](https://github.com/pavanvamsi3/triage4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22),
  then take on enhancements here and in the main [LLM4S](https://github.com/llm4s/llm4s) repository.

### Meet the maintainers and community

We meet every **Sunday at 9:00 AM London time** for Dev Hours.

- **Dev Hours calendar:** [luma.com/calendar/cal-Zd9BLb5jbZewxLA](https://luma.com/calendar/cal-Zd9BLb5jbZewxLA). Join the event for the date you want to attend.
- **Add every Dev Hour to your calendar:** open the
  [LLM4S Dev Hour weekly calendar link](https://api2.luma.com/ics/get?entity=calendar&id=cal-Zd9BLb5jbZewxLA). It adds
  all the events for 2026 to your Google Calendar, so you don't miss a Dev Hour.

### Links

- **Global AI Discord community** (~400 members): [discord.com/invite/DjPMufnhG6](https://discord.com/invite/DjPMufnhG6).
  Please feel free to invite anyone who may benefit from joining our community.
- **Website and documentation:** [llm4s.org](https://llm4s.org/)
- **GitHub organization:** [github.com/llm4s](https://github.com/llm4s)
- **Main repository:** [github.com/llm4s/llm4s](https://github.com/llm4s/llm4s)

### Support the project

- **Open Collective:** [opencollective.com/llm4s](https://opencollective.com/llm4s). If you find LLM4S useful, even a
  $1 donation would really help support the project.
- If your company is looking for an AI framework, please help us connect.

## License

Triage4s is released under the [MIT License](LICENSE), the same license as [LLM4S](https://github.com/llm4s/llm4s).
