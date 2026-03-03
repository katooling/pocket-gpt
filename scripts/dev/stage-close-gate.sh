#!/usr/bin/env bash
set -euo pipefail

PR_BODY_FILE="${1:-}"
if [[ -z "${PR_BODY_FILE}" || ! -f "${PR_BODY_FILE}" ]]; then
  echo "Usage: $0 <pr-body-file>" >&2
  exit 1
fi

if ! rg -qi 'stage close:\s*yes' "${PR_BODY_FILE}"; then
  echo "Stage close gate not requested; skipping."
  exit 0
fi

EVIDENCE_PATH="$(rg -o 'docs/operations/evidence/wp-[0-9]{2}/[0-9]{4}-[0-9]{2}-[0-9]{2}[^ )]*\.md' "${PR_BODY_FILE}" | head -n 1 || true)"
if [[ -z "${EVIDENCE_PATH}" ]]; then
  echo "Stage-close PR must link a WP evidence markdown file." >&2
  exit 1
fi

if [[ ! -f "${EVIDENCE_PATH}" ]]; then
  echo "Linked evidence file does not exist in repo: ${EVIDENCE_PATH}" >&2
  exit 1
fi

bash scripts/dev/evidence-check.sh "${EVIDENCE_PATH}"

echo "Stage-close evidence gate passed."
