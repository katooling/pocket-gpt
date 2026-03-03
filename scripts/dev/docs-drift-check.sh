#!/usr/bin/env bash
set -euo pipefail

CANONICAL_DOCS=(
  "scripts/dev/README.md"
  "docs/testing/test-strategy.md"
  "docs/testing/android-dx-and-test-playbook.md"
)

NON_CANONICAL=(
  "README.md"
  "docs/README.md"
  "docs/operations/README.md"
  "docs/testing/just-cli-android-validation-plan.md"
)

required_pointer='Source of truth'
violations=0

for file in "${NON_CANONICAL[@]}"; do
  [[ -f "${file}" ]] || continue

  if ! rg -q "${required_pointer}" "${file}"; then
    echo "Missing source-of-truth pointer in ${file}" >&2
    violations=1
  fi

  if rg -q '^```bash$' "${file}"; then
    if rg -n "scripts/dev/test\.sh|scripts/dev/device-test\.sh|run_short_loop\.sh|evaluate_thresholds\.py" "${file}" >/dev/null; then
      echo "Duplicated runnable command docs detected in non-canonical file: ${file}" >&2
      violations=1
    fi
  fi

done

for canonical in "${CANONICAL_DOCS[@]}"; do
  if [[ ! -f "${canonical}" ]]; then
    echo "Missing canonical file: ${canonical}" >&2
    violations=1
  fi
done

if [[ "${violations}" -ne 0 ]]; then
  exit 1
fi

echo "Docs drift check passed."
