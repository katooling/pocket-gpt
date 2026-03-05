#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

APP_PACKAGE="com.pocketagent.android"
APP_ACTIVITY="${APP_PACKAGE}/.MainActivity"
INSTALL_TASK=":apps:mobile-android:installDebug"
NO_INSTALL=0
FULL_LOGCAT=0
FILTER_PATTERN='com\.pocketagent\.android|AndroidRuntime|FATAL EXCEPTION|ANR in|OutOfMemoryError|PocketAgent|NATIVE_JNI|STAGE2_METRIC'

usage() {
  cat <<'EOF'
Usage: bash scripts/android/dev_loop.sh [options]

Options:
  --serial <adb-serial>  Target a specific device (also sets ADB_SERIAL).
  --no-install           Skip Gradle installDebug and only launch + tail logs.
  --full-logcat          Stream full logcat (no filtering).
  --filter <regex>       Custom regex filter for logcat (default is app-focused).
  -h, --help             Show help.

Examples:
  bash scripts/android/dev_loop.sh
  bash scripts/android/dev_loop.sh --serial RR8NB087YTF
  bash scripts/android/dev_loop.sh --no-install --filter "com.pocketagent.android|FATAL EXCEPTION"
EOF
}

has_rg() {
  command -v rg >/dev/null 2>&1
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --serial)
      export ADB_SERIAL="$2"
      shift 2
      ;;
    --no-install)
      NO_INSTALL=1
      shift
      ;;
    --full-logcat)
      FULL_LOGCAT=1
      shift
      ;;
    --filter)
      FILTER_PATTERN="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

cd "${ROOT_DIR}"

SERIAL="$(bash "${SCRIPT_DIR}/ensure_device.sh")"
echo "Device: ${SERIAL}"

if [[ "${NO_INSTALL}" -eq 0 ]]; then
  echo "Installing debug build (${INSTALL_TASK})..."
  ./gradlew --no-daemon "${INSTALL_TASK}"
else
  echo "Skipping installDebug (--no-install)."
fi

echo "Clearing previous logcat buffer..."
adb -s "${SERIAL}" logcat -c

echo "Launching ${APP_ACTIVITY}..."
if ! adb -s "${SERIAL}" shell am start -W -n "${APP_ACTIVITY}"; then
  echo "am start failed, falling back to launcher intent via monkey..."
  adb -s "${SERIAL}" shell monkey -p "${APP_PACKAGE}" -c android.intent.category.LAUNCHER 1
fi

echo "Verifying top resumed activity..."
adb -s "${SERIAL}" shell dumpsys activity activities | rg -n "topResumedActivity|ResumedActivity|${APP_PACKAGE}/\\.MainActivity" -m 20 || true

echo
echo "Streaming logcat. Press Ctrl+C to stop."
if [[ "${FULL_LOGCAT}" -eq 1 ]]; then
  adb -s "${SERIAL}" logcat
elif has_rg; then
  adb -s "${SERIAL}" logcat | rg --line-buffered "${FILTER_PATTERN}"
else
  adb -s "${SERIAL}" logcat | grep --line-buffered -E "${FILTER_PATTERN}"
fi
