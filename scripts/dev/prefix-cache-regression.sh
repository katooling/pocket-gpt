#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE' >&2
Usage:
  bash scripts/dev/prefix-cache-regression.sh --device <serial> [--run-dir <path>] [--install-mode auto|force|skip] [-- <extra stage2 args>]

Runs the smallest real-device benchmark that must prove shared-session prefix reuse.

Required environment:
  POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH=/absolute/device/path/model.gguf
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

DEVICE=""
RUN_DIR=""
INSTALL_MODE="${POCKETGPT_PREFIX_CACHE_REGRESSION_INSTALL_MODE:-auto}"
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
      shift 2
      ;;
    --run-dir)
      RUN_DIR="${2:-}"
      shift 2
      ;;
    --install-mode)
      INSTALL_MODE="${2:-}"
      shift 2
      ;;
    --)
      shift
      EXTRA_ARGS+=("$@")
      break
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ -z "${DEVICE}" ]]; then
  echo "Missing required flag: --device" >&2
  usage
  exit 1
fi

cmd=(
  bash "${REPO_ROOT}/scripts/android/run_stage2_native.sh"
  --device "${DEVICE}"
  --profile quick
  --models 0.8b
  --scenarios a
  --session-mode shared
  --disable-tools 1
  --require-prefix-cache-hit 1
  --install-mode "${INSTALL_MODE}"
)

if [[ -n "${RUN_DIR}" ]]; then
  cmd+=(--run-dir "${RUN_DIR}")
fi

cmd+=("${EXTRA_ARGS[@]}")
"${cmd[@]}"
