# Triage4s

A small Scala library for evidence-backed AI triage, with a local dashboard for real GitHub issues.

**LLM4S connects to the model. Triage4s defines the triage rules and validates the answer.**
The demo uses public issues from [pavanvamsi3/copilot-lens](https://github.com/pavanvamsi3/copilot-lens)
and never writes to GitHub.

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

Requirements: **JDK 21** and **sbt**. The build pins Scala 3.7.1 and published LLM4S 0.4.1;
no sibling LLM4S checkout or `publishLocal` step is needed.

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

On macOS with Homebrew's keg-only JDK:

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

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

## Limits and reliability

- Up to **10 distinct selected issues**, one active run, one issue at a time.
- Up to **20,000 combined title/body characters**; oversized input fails rather than being silently truncated.
- Up to **1,200 output tokens**; truncated/invalid JSON is an error, not automatically repaired.
- GitHub loads **30 entries per page, at most 3 pages**. Pull requests are filtered out, so the issue
  count may be lower. The UI warns that results are capped; this is not an exhaustive repository scan.
- No Triage4s retries. The standalone demo sets the pinned Azure SDK's retry count to zero at startup,
  before constructing LLM4S's OpenAI client.
- Transport timeouts: 10-second connect; 60-second response, read, and write phases.
  These are **phase limits, not a total wall-clock deadline**.
- Transport configuration is process-wide in the forked demo JVM because LLM4S 0.4.1 does not expose
  the SDK builder. The reusable library does not change global settings; configure your injected client's
  transport policy yourself.
- Runs are in memory and disappear on restart. Saving a snapshot or recording replaces the previous
  local `latest.json`; it is not an archive.
- The pinned OpenAI path uses the Azure SDK underneath LLM4S. It is still calling OpenAI, not Azure-hosted models.

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
and coverage, with counts and denominators. Missing results remain in accuracy denominators and are not
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
