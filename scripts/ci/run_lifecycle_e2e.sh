#!/usr/bin/env bash
set -euo pipefail

FLOW_PATH="tests/maestro/scenario-first-run-download-chat.yaml"
OUT_DIR="tmp/lifecycle-e2e-first-run"
APP_ID="com.pocketagent.android"
APP_TEST_ID="com.pocketagent.android.test"
RISK_REASON="${1:-unknown}"
ATTEMPT_TIMEOUT_SEC="${LIFECYCLE_E2E_ATTEMPT_TIMEOUT_SEC:-1200}"
ADB_TIMEOUT_SEC="${LIFECYCLE_E2E_ADB_TIMEOUT_SEC:-120}"

mkdir -p "${OUT_DIR}"

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "${APK_PATH}" ]]; then
  echo "No debug APK found under apps/mobile-android/build/outputs/apk/debug" >&2
  exit 1
fi

DEVICE_SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [[ -z "${DEVICE_SERIAL}" ]]; then
  echo "No connected emulator/device for Maestro run." >&2
  exit 1
fi

if ! timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" install -r "${APK_PATH}" >/dev/null; then
  echo "Failed to install APK on ${DEVICE_SERIAL} within ${ADB_TIMEOUT_SEC}s." >&2
  exit 1
fi

run_attempt() {
  local attempt="$1"
  local attempt_dir="${OUT_DIR}/attempt-${attempt}"
  mkdir -p "${attempt_dir}"
  set +e
  timeout "${ATTEMPT_TIMEOUT_SEC}" maestro --device "${DEVICE_SERIAL}" test "${FLOW_PATH}" --format junit --debug-output "${attempt_dir}/debug" \
    > "${attempt_dir}/junit.xml" \
    2> "${attempt_dir}/maestro-stderr.log"
  local rc=$?
  set -e
  if [[ ${rc} -eq 124 ]]; then
    echo "::warning::Lifecycle E2E attempt ${attempt} timed out after ${ATTEMPT_TIMEOUT_SEC}s."
  fi
  return ${rc}
}

if run_attempt 1; then
  printf "decision=%s\nreason=%s\nfirst_attempt_failed=%s\nfinal_attempt=%s\n" \
    "run" "${RISK_REASON}" "false" "1" > "${OUT_DIR}/retry-summary.txt"
  exit 0
fi

echo "::warning::First lifecycle E2E attempt failed; retrying once after clean-state reset."
printf "decision=%s\nreason=%s\nfirst_attempt_failed=%s\n" \
  "run" "${RISK_REASON}" "true" > "${OUT_DIR}/retry-summary.txt"

timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell pm clear "${APP_ID}" >/dev/null || true
timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" uninstall "${APP_ID}" >/dev/null || true
timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" uninstall "${APP_TEST_ID}" >/dev/null || true
if ! timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" install -r "${APK_PATH}" >/dev/null; then
  echo "Failed to reinstall APK on ${DEVICE_SERIAL} within ${ADB_TIMEOUT_SEC}s." >&2
  exit 1
fi

if run_attempt 2; then
  echo "final_attempt=2" >> "${OUT_DIR}/retry-summary.txt"
  exit 0
fi

echo "final_attempt=none" >> "${OUT_DIR}/retry-summary.txt"
exit 1
