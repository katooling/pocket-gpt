#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

APP_PACKAGE="com.pocketagent.android"
APP_ACTIVITY="${APP_PACKAGE}/.MainActivity"
INSTALL_TASK=":apps:mobile-android:installDebug"
NO_INSTALL=0
FULL_LOGCAT=0
FILTER_PATTERN='AndroidRuntime|FATAL EXCEPTION|ANR in|OutOfMemoryError|PocketAgent|NATIVE_JNI|STAGE2_METRIC|RuntimeGateway|NativeJniLlamaCppBridge|ChatViewModel'
INSTALL_LOG=""
MODULE_OUTPUT_REPAIR_SCRIPT="${ROOT_DIR}/scripts/dev/ensure_jvm_module_outputs.sh"

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

print_install_restricted_help() {
  cat <<'EOF'
Install was blocked by the device, not by app code.

ADB reported: INSTALL_FAILED_USER_RESTRICTED (install canceled by user)

Usual fixes on Android devices:
  1. Unlock the phone and keep the screen on while installing.
  2. Accept any install confirmation dialog shown on-device.
  3. In Developer options, enable the setting that allows installs from USB/ADB.
  4. If Play Protect or device policy blocks ADB installs, temporarily allow this debug install.

If the app is already installed and you only want to relaunch it, rerun with:
  bash scripts/android/dev_loop.sh --serial "$ADB_SERIAL" --no-install
EOF
}

resolve_app_pid() {
  local serial="$1"
  local pid
  pid="$(adb -s "${serial}" shell pidof -s "${APP_PACKAGE}" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [[ -n "${pid}" ]]; then
    echo "${pid}"
    return 0
  fi
  return 1
}

run_install_task() {
  local log_file
  log_file="$(mktemp -t pocketgpt-install.XXXXXX.log)"
  INSTALL_LOG="$log_file"
  bash "${MODULE_OUTPUT_REPAIR_SCRIPT}"
  if ./gradlew --no-daemon "${INSTALL_TASK}" >"$log_file" 2>&1; then
    rm -f "$log_file"
    INSTALL_LOG=""
    return 0
  fi

  if has_rg && rg -q 'Unresolved reference .*InferenceRequest|Unresolved reference .*InferenceModule|Unresolved reference .*ModelCatalog|Execution failed for task .*(inference-adapters|native-bridge):compileKotlin' "$log_file"; then
    echo "Detected stale JVM dependency outputs. Rebuilding package jars and retrying once..."
    bash "${MODULE_OUTPUT_REPAIR_SCRIPT}" --force
    if ./gradlew --no-daemon "${INSTALL_TASK}" >"$log_file" 2>&1; then
      rm -f "$log_file"
      INSTALL_LOG=""
      return 0
    fi
  fi

  cat "$log_file"
  if has_rg && rg -q 'INSTALL_FAILED_USER_RESTRICTED|Install canceled by user' "$log_file"; then
    echo >&2
    print_install_restricted_help >&2
  fi
  return 1
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
  run_install_task
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
elif APP_PID="$(resolve_app_pid "${SERIAL}")"; then
  echo "Using app PID scoped logcat (pid=${APP_PID})."
  adb -s "${SERIAL}" logcat --pid="${APP_PID}"
elif has_rg; then
  adb -s "${SERIAL}" logcat | rg --line-buffered "${FILTER_PATTERN}"
else
  adb -s "${SERIAL}" logcat | grep --line-buffered -E "${FILTER_PATTERN}"
fi
