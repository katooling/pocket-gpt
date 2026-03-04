#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE' >&2
Usage:
  bash scripts/android/provision_sideload_models.sh \
    --device <serial> \
    --model-0-8b-local <host-path-to-gguf> \
    --model-2b-local <host-path-to-gguf> \
    [--device-dir <device-directory>]

Example:
  bash scripts/android/provision_sideload_models.sh \
    --device RR8NB087YTF \
    --model-0-8b-local /models/qwen3.5-0.8b-q4.gguf \
    --model-2b-local /models/qwen3.5-2b-q4.gguf \
    --device-dir /sdcard/Download/pocketgpt-models
USAGE
}

DEVICE=""
MODEL_0_8B_LOCAL=""
MODEL_2B_LOCAL=""
DEVICE_DIR="/sdcard/Download/pocketgpt-models"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
      shift 2
      ;;
    --model-0-8b-local)
      MODEL_0_8B_LOCAL="${2:-}"
      shift 2
      ;;
    --model-2b-local)
      MODEL_2B_LOCAL="${2:-}"
      shift 2
      ;;
    --device-dir)
      DEVICE_DIR="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown flag: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${DEVICE}" || -z "${MODEL_0_8B_LOCAL}" || -z "${MODEL_2B_LOCAL}" ]]; then
  echo "Missing required arguments." >&2
  usage
  exit 1
fi

if [[ ! -f "${MODEL_0_8B_LOCAL}" ]]; then
  echo "Missing local model file: ${MODEL_0_8B_LOCAL}" >&2
  exit 1
fi
if [[ ! -f "${MODEL_2B_LOCAL}" ]]; then
  echo "Missing local model file: ${MODEL_2B_LOCAL}" >&2
  exit 1
fi

MODEL_0_8B_DEVICE_PATH="${DEVICE_DIR}/qwen3.5-0.8b-q4.gguf"
MODEL_2B_DEVICE_PATH="${DEVICE_DIR}/qwen3.5-2b-q4.gguf"

echo "Ensuring destination directory exists on device..."
adb -s "${DEVICE}" shell "mkdir -p '${DEVICE_DIR}'" >/dev/null

echo "Pushing 0.8B model to ${MODEL_0_8B_DEVICE_PATH}..."
adb -s "${DEVICE}" push "${MODEL_0_8B_LOCAL}" "${MODEL_0_8B_DEVICE_PATH}" >/dev/null

echo "Pushing 2B model to ${MODEL_2B_DEVICE_PATH}..."
adb -s "${DEVICE}" push "${MODEL_2B_LOCAL}" "${MODEL_2B_DEVICE_PATH}" >/dev/null

echo "Verifying files on device..."
adb -s "${DEVICE}" shell "test -f '${MODEL_0_8B_DEVICE_PATH}' && test -f '${MODEL_2B_DEVICE_PATH}'"

echo
echo "Export these variables before running stage2:"
echo "export POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH=${MODEL_0_8B_DEVICE_PATH}"
echo "export POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH=${MODEL_2B_DEVICE_PATH}"
echo
echo "Then run:"
echo "bash scripts/dev/bench.sh stage2 --device ${DEVICE} --date $(date +%F)"
