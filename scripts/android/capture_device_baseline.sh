#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

SERIAL="$(bash scripts/android/ensure_device.sh)"
STAMP="$(date +%Y%m%d-%H%M%S)"
DATE_DIR="$(date +%F)"
OUT_DIR="${1:-scripts/benchmarks/runs/${DATE_DIR}/${SERIAL}}"
OUT_FILE="${OUT_DIR}/baseline-${STAMP}.txt"

mkdir -p "${OUT_DIR}"

{
  echo "timestamp=${STAMP}"
  echo "serial=${SERIAL}"
  echo
  echo "[device.props]"
  adb -s "${SERIAL}" shell getprop ro.product.manufacturer
  adb -s "${SERIAL}" shell getprop ro.product.model
  adb -s "${SERIAL}" shell getprop ro.product.device
  adb -s "${SERIAL}" shell getprop ro.build.version.release
  adb -s "${SERIAL}" shell getprop ro.build.version.security_patch
  echo
  echo "[battery]"
  adb -s "${SERIAL}" shell dumpsys battery | sed -n '1,40p'
  echo
  echo "[thermal]"
  adb -s "${SERIAL}" shell dumpsys thermalservice | sed -n '1,80p'
  echo
  echo "[memory]"
  adb -s "${SERIAL}" shell cat /proc/meminfo | sed -n '1,20p'
  echo
  echo "[storage]"
  adb -s "${SERIAL}" shell df -h /data | sed -n '1,10p'
  echo
  echo "[benchmark.settings]"
  echo -n "stay_on_while_plugged_in="
  adb -s "${SERIAL}" shell settings get global stay_on_while_plugged_in
  echo -n "window_animation_scale="
  adb -s "${SERIAL}" shell settings get global window_animation_scale
  echo -n "transition_animation_scale="
  adb -s "${SERIAL}" shell settings get global transition_animation_scale
  echo -n "animator_duration_scale="
  adb -s "${SERIAL}" shell settings get global animator_duration_scale
  echo -n "screen_off_timeout="
  adb -s "${SERIAL}" shell settings get system screen_off_timeout
} > "${OUT_FILE}"

echo "Saved baseline to ${OUT_FILE}"
