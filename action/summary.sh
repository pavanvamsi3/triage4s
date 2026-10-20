#!/usr/bin/env bash
# Renders the JSON of `triage4s classify --json` as the Markdown the GitHub Action writes to the job summary and, in
# comment mode, to the issue (#100). Usage: action/summary.sh RESULT.json
set -euo pipefail
if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 RESULT.json" >&2
  exit 2
fi
# the program is jq, not shell: its $ and backticks are meant literally
# shellcheck disable=SC2016
jq -r '
  .response as $r | $r.result as $t |
  def code: "`" + . + "`";
  def quote: gsub("[\\r\\n]+"; " ") | if length > 300 then .[0:297] + "..." else . end;
  [
    "### Triage4s suggestion for #\(.id | split("#") | last)",
    "",
    "| | Suggestion |",
    "|---|---|",
    "| Type | \(if $t.issueType then ($t.issueType | code) else "_abstained_" end) |",
    "| Component | \(if $t.component then ($t.component | code) else "_abstained_" end) |",
    "",
    "**Why:** \($t.rationale | quote)",
    ""
  ]
  + (if ($t.evidence | length) > 0
     then ["**Evidence** (exact quotes from the issue):", ""] + [$t.evidence[] | "- \(.target) from the \(.field): \"\(.quote | quote)\""] + [""]
     else [] end)
  + (if $t.needsReview
     then ["**Needs human review:**", ""] + [$t.reviewReasons[] | "- \(. | quote)"] + [""]
     else [] end)
  + ["<sub>Suggested by \($r.model | code) through Triage4s. A suggestion, not a decision: no labels were changed.</sub>"]
  | join("\n")
' "$1"
