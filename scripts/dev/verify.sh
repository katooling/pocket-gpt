#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# Compatibility wrapper; prefer `bash scripts/dev/test.sh`.
bash scripts/dev/test.sh ci
