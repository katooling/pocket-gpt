#!/usr/bin/env bash
set -euo pipefail

if [[ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]]; then
  echo "Evidence changed-file check skipped: not a pull_request event."
  exit 0
fi

if [[ -z "${GITHUB_BASE_REF:-}" ]]; then
  echo "Evidence changed-file check skipped: GITHUB_BASE_REF is unset."
  exit 0
fi

git fetch --no-tags --depth=1 origin "${GITHUB_BASE_REF}" >/dev/null 2>&1 || true

CHANGED_EVIDENCE_FILES="$(git diff --name-only "origin/${GITHUB_BASE_REF}...HEAD" | rg '^docs/operations/evidence/.+\.md$' || true)"
if [[ -z "${CHANGED_EVIDENCE_FILES}" ]]; then
  echo "No changed evidence markdown files detected."
  exit 0
fi

status=0
while IFS= read -r evidence_file; do
  [[ -z "${evidence_file}" ]] && continue

  if [[ ! -f "${evidence_file}" ]]; then
    echo "Changed evidence file not present in checkout (possibly deleted): ${evidence_file}"
    continue
  fi

  if rg -q 'scripts/benchmarks/runs/' "${evidence_file}"; then
    if ! bash scripts/dev/evidence-check.sh "${evidence_file}"; then
      status=1
    fi
  else
    echo "No raw run artifact references in ${evidence_file}; skipping path validation."
  fi
done <<< "${CHANGED_EVIDENCE_FILES}"

exit "${status}"
