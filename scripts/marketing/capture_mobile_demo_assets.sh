#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR=""
SERIAL=""
RECORD_SECONDS="20"

usage() {
  cat <<USAGE
Usage:
  bash scripts/marketing/capture_mobile_demo_assets.sh --output <dir> [--serial <adb_serial>] [--record-seconds <n>]

Examples:
  bash scripts/marketing/capture_mobile_demo_assets.sh --output docs/operations/assets/mkt-04/2026-03-04
  bash scripts/marketing/capture_mobile_demo_assets.sh --output docs/operations/assets/mkt-04/2026-03-04 --serial RR8NB087YTF --record-seconds 30
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --serial)
      SERIAL="$2"
      shift 2
      ;;
    --record-seconds)
      RECORD_SECONDS="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$OUTPUT_DIR" ]]; then
  echo "Missing required --output" >&2
  usage
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not installed or not in PATH" >&2
  exit 1
fi

if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [[ -z "$SERIAL" ]]; then
  echo "No connected adb device found. Connect a device or provide --serial." >&2
  exit 1
fi

ADB=(adb -s "$SERIAL")

mkdir -p "$OUTPUT_DIR"

echo "Using device: $SERIAL"
echo "Output directory: $OUTPUT_DIR"

echo
echo "Step 1/3: Navigate the app to the first useful state, then press Enter to capture screenshot-01."
read -r
"${ADB[@]}" exec-out screencap -p > "$OUTPUT_DIR/screenshot-01.png"


echo "Step 2/3: Navigate the app to the second useful state, then press Enter to capture screenshot-02."
read -r
"${ADB[@]}" exec-out screencap -p > "$OUTPUT_DIR/screenshot-02.png"

TMP_REMOTE="/sdcard/pocketgpt-demo.mp4"
echo "Step 3/3: Press Enter to start a ${RECORD_SECONDS}s screen recording. Interact with the app while recording runs."
read -r
"${ADB[@]}" shell rm -f "$TMP_REMOTE" >/dev/null 2>&1 || true
"${ADB[@]}" shell screenrecord --time-limit "$RECORD_SECONDS" "$TMP_REMOTE"
"${ADB[@]}" pull "$TMP_REMOTE" "$OUTPUT_DIR/demo.mp4" >/dev/null
"${ADB[@]}" shell rm -f "$TMP_REMOTE" >/dev/null 2>&1 || true

echo
ls -lh "$OUTPUT_DIR" | sed -n '1,20p'
echo "Capture complete."
