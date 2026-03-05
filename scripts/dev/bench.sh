#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 stage2 --device <id> [--date YYYY-MM-DD] [--profile quick|closure] [--models 0.8b|2b|both] [--scenarios a|b|both] [--resume] [--install-mode auto|force|skip] [--logcat filtered|full] [--evidence-note-path <abs-path>]" >&2
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

if [[ "$#" -lt 1 ]]; then
  usage
  exit 1
fi

STAGE="$1"
shift

if [[ "${STAGE}" != "stage2" ]]; then
  echo "Only stage2 is supported." >&2
  usage
  exit 1
fi

python3 tools/devctl/main.py lane stage2 "$@"
