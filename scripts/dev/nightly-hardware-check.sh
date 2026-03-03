#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

if ! command -v adb >/dev/null 2>&1; then
  echo "Nightly hardware lane prerequisite missing: adb not installed."
  exit 0
fi

if ! adb devices -l | rg -q '\sdevice\s'; then
  echo "Nightly hardware lane prerequisite missing: no authorized physical device attached."
  exit 0
fi

echo "Authorized device detected. Running device lane smoke."
bash scripts/dev/device-test.sh 1 nightly-hardware-smoke
