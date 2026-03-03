#!/usr/bin/env bash
set -euo pipefail

PR_BODY_FILE="${1:-}"
if [[ -z "${PR_BODY_FILE}" || ! -f "${PR_BODY_FILE}" ]]; then
  echo "Usage: $0 <pr-body-file>" >&2
  exit 1
fi

require_checked() {
  local pattern="$1"
  local message="$2"
  if ! rg -q -- "${pattern}" "${PR_BODY_FILE}"; then
    echo "PR template requirement failed: ${message}" >&2
    exit 1
  fi
}

require_checked '- \[x\] I ran `bash scripts/dev/test\.sh` \(or `bash scripts/dev/test\.sh ci`\) and it passed\.' "test command checkbox must be checked"
require_checked '- \[x\] I used canonical orchestrator lanes \(`python3 tools/devctl/main\.py lane \.\.\.`\) directly or via the `scripts/dev/\*` wrappers\.' "orchestrator lane checkbox must be checked"
require_checked '- \[x\] I updated docs affected by this change, or confirmed no docs changes are needed\.' "docs checkbox must be checked"

if rg -q '(?i)wp-|work package|stage' "${PR_BODY_FILE}"; then
  require_checked '- \[x\] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` and linked it below\.' "stage/work-package evidence checkbox must be checked"
  if ! rg -q 'docs/operations/evidence/' "${PR_BODY_FILE}"; then
    echo "Stage/work-package PR must include evidence note link." >&2
    exit 1
  fi
fi

echo "PR body validation passed."
