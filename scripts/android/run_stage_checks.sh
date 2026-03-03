#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[1/5] Checking adb availability..."
adb version

echo "[2/5] Verifying one adb-ready device..."
SERIAL="$(bash "${SCRIPT_DIR}/ensure_device.sh")"
echo "Using device serial: ${SERIAL}"

echo "[3/5] Reading device identity..."
adb -s "${SERIAL}" shell getprop ro.product.manufacturer
adb -s "${SERIAL}" shell getprop ro.product.model
adb -s "${SERIAL}" shell getprop ro.build.version.release

echo "[4/5] Reading battery info..."
adb -s "${SERIAL}" shell dumpsys battery | sed -n '1,25p'

echo "[5/5] Reading thermal summary..."
adb -s "${SERIAL}" shell dumpsys thermalservice | sed -n '1,40p' || true

echo "Stage check script completed."
