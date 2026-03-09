#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi

: "${MAESTRO_CLOUD_API_KEY:?Set MAESTRO_CLOUD_API_KEY in .env}"

BUILD_APK=1
API_LEVELS=(34 33)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)
      BUILD_APK=0
      shift
      ;;
    --api-level)
      API_LEVELS=("${2:?missing api level}")
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ ${BUILD_APK} -eq 1 ]]; then
  GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
fi

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "${APK_PATH}" || ! -f "${APK_PATH}" ]]; then
  echo "Debug APK not found." >&2
  exit 1
fi

mkdir -p tmp/maestro-cloud-gpu-benchmark

EXIT_CODE=0
for api_level in "${API_LEVELS[@]}"; do
  run_dir="tmp/maestro-cloud-gpu-benchmark/api-${api_level}"
  mkdir -p "${run_dir}"
  echo "Running Maestro Cloud GPU benchmark on Android API ${api_level}"
  if ! maestro cloud \
    --android-api-level "${api_level}" \
    --app-file "${APK_PATH}" \
    --flows tests/maestro-cloud/ \
    --format junit \
    --output "${run_dir}/junit.xml" | tee "${run_dir}/run.log"; then
    EXIT_CODE=1
  fi
done

exit "${EXIT_CODE}"
