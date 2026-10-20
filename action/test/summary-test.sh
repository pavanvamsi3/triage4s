#!/usr/bin/env bash
# The expected Markdown contains backticks, meant literally.
# shellcheck disable=SC2016
# Tests action/summary.sh against two fixtures: a suggestion with evidence, and a full abstention needing review.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fail=0
expect() { grep -qF -- "$2" <<<"$1" || { echo "FAIL: missing: $2"; fail=1; }; }
absent() { ! grep -qF -- "$2" <<<"$1" || { echo "FAIL: unexpected: $2"; fail=1; }; }

out="$("$here/../summary.sh" "$here/suggested.json")"
expect "$out" "### Triage4s suggestion for #12"
expect "$out" '| Type | `bug` |'
expect "$out" '| Component | `cli` |'
expect "$out" '- issueType from the title: "crashes"'
expect "$out" "no labels were changed"
absent "$out" "Needs human review"

out="$("$here/../summary.sh" "$here/review.json")"
expect "$out" "| Type | _abstained_ |"
expect "$out" "| Component | _abstained_ |"
expect "$out" "**Needs human review:**"
expect "$out" "- No component is named."
absent "$out" "**Evidence**"

if "$here/../summary.sh" 2>/dev/null; then echo "FAIL: no argument should be a usage error"; fail=1; fi
[ "$fail" -eq 0 ] && echo "summary.sh: all checks passed"
exit "$fail"
