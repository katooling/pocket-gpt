#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

source scripts/dev/maestro-gpu-matrix-common.sh

if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi

: "${MAESTRO_CLOUD_API_KEY:?Set MAESTRO_CLOUD_API_KEY in .env}"

BUILD_APK=1
DRY_RUN=0
ASYNC=0
APP_BINARY_ID="${MAESTRO_APP_BINARY_ID:-}"
API_LEVELS=(29 31 34)
MODEL_KEYS=(tiny qwen_0_8b qwen_2b)
PROJECT_ID="${MAESTRO_PROJECT_ID:-}"
FLOW_TEMPLATE="tests/maestro/shared/scenario-gpu-qualify-by-model.template.yaml"
OUTPUT_ROOT="tmp/maestro-cloud-gpu-model-matrix"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)
      BUILD_APK=0
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --async)
      ASYNC=1
      shift
      ;;
    --app-binary-id)
      APP_BINARY_ID="${2:?missing app binary id}"
      shift 2
      ;;
    --api-levels)
      IFS=',' read -r -a API_LEVELS <<< "${2:?missing api levels}"
      shift 2
      ;;
    --models)
      IFS=',' read -r -a MODEL_KEYS <<< "${2:?missing model keys}"
      shift 2
      ;;
    --project-id)
      PROJECT_ID="${2:?missing project id}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ ${BUILD_APK} -eq 1 && -z "${APP_BINARY_ID}" && ${DRY_RUN} -eq 0 ]]; then
  GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
fi

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' 2>/dev/null | sort | head -n 1)"
if [[ ${DRY_RUN} -eq 0 && -z "${APP_BINARY_ID}" && ( -z "${APK_PATH}" || ! -f "${APK_PATH}" ) ]]; then
  echo "Debug APK not found." >&2
  exit 1
fi

mkdir -p "${OUTPUT_ROOT}" tmp/maestro-cloud-generated
EXIT_CODE=0

for api_level in "${API_LEVELS[@]}"; do
  for model_key in "${MODEL_KEYS[@]}"; do
    spec="$(pocketgpt_gpu_matrix_model_spec "${model_key}")"
    model_id="${spec%%|*}"
    version="${spec##*|}"
    run_tag="api${api_level}-${model_key}"
    run_dir="${OUTPUT_ROOT}/${run_tag}"
    flow_path="tmp/maestro-cloud-generated/${run_tag}.yaml"
    mkdir -p "${run_dir}"
    pocketgpt_gpu_matrix_make_flow "${flow_path}" "${FLOW_TEMPLATE}" "${model_id}" "${version}" "${run_tag}"

    cmd=(maestro cloud --api-key "${MAESTRO_CLOUD_API_KEY}" --android-api-level "${api_level}" --flows "${flow_path}")
    if [[ ${ASYNC} -eq 1 ]]; then
      cmd+=(--async)
    else
      cmd+=(--format junit --output "${run_dir}/junit.xml")
    fi
    if [[ -n "${PROJECT_ID}" ]]; then
      cmd+=(--project-id "${PROJECT_ID}")
    fi
    if [[ -n "${APP_BINARY_ID}" ]]; then
      cmd+=(--app-binary-id "${APP_BINARY_ID}")
    else
      cmd+=(--app-file "${APK_PATH}")
    fi

    echo "Running ${model_key} on Android API ${api_level}"
    if [[ ${DRY_RUN} -eq 1 ]]; then
      printf 'DRY_RUN '
      printf '%q ' "${cmd[@]}"
      printf '\n'
      continue
    fi
    if ! "${cmd[@]}" | tee "${run_dir}/run.log"; then
      EXIT_CODE=1
    fi
  done
done

exit "${EXIT_CODE}"
