#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 <evidence-markdown-file>" >&2
  exit 1
fi

EVIDENCE_FILE="$1"
if [[ ! -f "${EVIDENCE_FILE}" ]]; then
  echo "Evidence file not found: ${EVIDENCE_FILE}" >&2
  exit 1
fi

RUN_PATHS_RAW="$(rg -o 'scripts/benchmarks/runs/[^ )`"]+' "${EVIDENCE_FILE}" | sort -u || true)"

if [[ -z "${RUN_PATHS_RAW}" ]]; then
  echo "No raw run artifact paths referenced in ${EVIDENCE_FILE}" >&2
  exit 1
fi

missing=0
while IFS= read -r path; do
  [[ -z "${path}" ]] && continue
  if [[ ! -e "${path}" ]]; then
    echo "Missing referenced artifact path: ${path}" >&2
    missing=1
  fi
done <<< "${RUN_PATHS_RAW}"

if [[ "${missing}" -ne 0 ]]; then
  exit 1
fi

echo "Evidence check passed: ${EVIDENCE_FILE}"
