#!/usr/bin/env bash
set -euo pipefail

echo "[1/4] Checking adb availability..."
adb version

echo "[2/4] Listing connected devices..."
adb devices

echo "[3/4] Reading battery info..."
adb shell dumpsys battery | sed -n '1,25p'

echo "[4/4] Reading thermal summary..."
adb shell dumpsys thermalservice | sed -n '1,40p' || true

echo "Stage check script completed."
