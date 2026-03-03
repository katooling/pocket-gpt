#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${1:-com.pocketagent}"
DURATION_SECONDS="${2:-120}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="scripts/benchmarks/logcat-${PACKAGE_NAME}-${STAMP}.txt"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="$(bash "${SCRIPT_DIR}/ensure_device.sh")"

echo "Capturing logcat for package ${PACKAGE_NAME} from ${SERIAL} for ${DURATION_SECONDS}s..."
adb -s "${SERIAL}" logcat -c
adb -s "${SERIAL}" logcat | rg "${PACKAGE_NAME}|Inference|Routing|Tool|Memory|Policy" > "${OUT_FILE}" &
LOG_PID=$!
sleep "${DURATION_SECONDS}"
kill "${LOG_PID}" || true

echo "Saved logcat to ${OUT_FILE}"
