#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

usage() {
  echo "Usage: $0 [fast|core|merge|auto|full|quick|ci]" >&2
}

MODE="${1:-merge}"

if [[ "$#" -gt 1 ]]; then
  usage
  exit 1
fi

python3 tools/devctl/main.py lane test "${MODE}"
