#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${REPO_ROOT}/.gradle-home}"
MODE="${1:-full}"

if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="${ANDROID_SDK_ROOT}"
fi

COMMON_TASKS=(
  :packages:core-domain:test
  :packages:inference-adapters:test
  :packages:tool-runtime:test
  :packages:memory:test
  :apps:mobile-android-host:test
)

ANDROID_TASKS=(
  :apps:mobile-android:testDebugUnitTest
)

run_common() {
  if [[ "${MODE}" == "full" || "${MODE}" == "ci" ]]; then
    ./gradlew --no-daemon clean "${COMMON_TASKS[@]}"
  else
    ./gradlew --no-daemon "${COMMON_TASKS[@]}"
  fi
}

run_with_android() {
  if [[ "${MODE}" == "full" || "${MODE}" == "ci" ]]; then
    ./gradlew --no-daemon clean "${COMMON_TASKS[@]}" "${ANDROID_TASKS[@]}"
  else
    ./gradlew --no-daemon "${COMMON_TASKS[@]}" "${ANDROID_TASKS[@]}"
  fi
}

case "${MODE}" in
  full|ci|quick)
    if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]]; then
      run_with_android
    else
      echo "Android SDK not configured; running host/JVM test lane only." >&2
      run_common
    fi
    ;;
  *)
    echo "Usage: $0 [full|quick|ci]" >&2
    exit 1
    ;;
esac
