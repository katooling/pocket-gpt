#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [--repeats N]" >&2
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

python3 tools/devctl/main.py lane journey "$@"
