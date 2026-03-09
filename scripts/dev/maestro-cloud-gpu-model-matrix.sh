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
APP_BINARY_ID="${MAESTRO_APP_BINARY_ID:-}"
API_LEVELS=(29 31 34)
MODEL_KEYS=(tiny qwen_0_8b qwen_2b)
DEVICE_MODEL="${MAESTRO_DEVICE_MODEL:-}"
DEVICE_OS="${MAESTRO_DEVICE_OS:-}"
PROJECT_ID="${MAESTRO_PROJECT_ID:-}"
FLOW_TEMPLATE="tests/maestro-cloud/scenario-gpu-probe-by-model.template.yaml"
OUTPUT_ROOT="tmp/maestro-cloud-gpu-model-matrix"
PROMPT='Reply with exactly: GPU_MATRIX_OK'

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)
      BUILD_APK=0
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
    --device-model)
      DEVICE_MODEL="${2:?missing device model}"
      shift 2
      ;;
    --device-os)
      DEVICE_OS="${2:?missing device os}"
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

if [[ ${BUILD_APK} -eq 1 && -z "${APP_BINARY_ID}" ]]; then
  GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
fi

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "${APP_BINARY_ID}" && ( -z "${APK_PATH}" || ! -f "${APK_PATH}" ) ]]; then
  echo "Debug APK not found." >&2
  exit 1
fi

model_spec() {
  case "$1" in
    tiny)
      printf '%s\n' 'smollm2-135m-instruct-q4_k_m|q4_k_m'
      ;;
    qwen_0_8b)
      printf '%s\n' 'qwen3.5-0.8b-q4|q4_0'
      ;;
    qwen_2b)
      printf '%s\n' 'qwen3.5-2b-q4|q4_0'
      ;;
    qwen_0_8b_tiny)
      printf '%s\n' 'qwen3.5-0.8b-q4|ud_iq2_xxs'
      ;;
    qwen_2b_tiny)
      printf '%s\n' 'qwen3.5-2b-q4|ud_iq2_xxs'
      ;;
    *)
      echo "Unsupported model key: $1" >&2
      exit 1
      ;;
  esac
}

make_flow() {
  local output_path="$1"
  local model_id="$2"
  local version="$3"
  local run_tag="$4"
  local download_row="${model_id} • ${version}"
  sed \
    -e "s|__TARGET_DOWNLOAD_ROW__|${download_row}|g" \
    -e "s|__TARGET_DOWNLOAD_START__|Start download ${model_id} ${version}|g" \
    -e "s|__TARGET_INSTALLED_ROW__|Installed version ${model_id} ${version}|g" \
    -e "s|__TARGET_ACTIVATE_BUTTON__|Activate version ${model_id} ${version}|g" \
    -e "s|__RUN_TAG__|${run_tag}|g" \
    -e "s|__PROMPT__|${PROMPT}|g" \
    "${FLOW_TEMPLATE}" > "${output_path}"
}

mkdir -p "${OUTPUT_ROOT}" tmp/maestro-cloud-generated
EXIT_CODE=0

for api_level in "${API_LEVELS[@]}"; do
  for model_key in "${MODEL_KEYS[@]}"; do
    spec="$(model_spec "${model_key}")"
    model_id="${spec%%|*}"
    version="${spec##*|}"
    run_tag="api${api_level}-${model_key}"
    run_dir="${OUTPUT_ROOT}/${run_tag}"
    flow_path="tmp/maestro-cloud-generated/${run_tag}.yaml"
    mkdir -p "${run_dir}"
    make_flow "${flow_path}" "${model_id}" "${version}" "${run_tag}"

    cmd=(maestro cloud --api-key "${MAESTRO_CLOUD_API_KEY}" --android-api-level "${api_level}" --flows "${flow_path}" --format junit --output "${run_dir}/junit.xml")
    if [[ -n "${PROJECT_ID}" ]]; then
      cmd+=(--project-id "${PROJECT_ID}")
    fi
    if [[ -n "${DEVICE_MODEL}" ]]; then
      cmd+=(--device-model "${DEVICE_MODEL}")
    fi
    if [[ -n "${DEVICE_OS}" ]]; then
      cmd+=(--device-os "${DEVICE_OS}")
    fi
    if [[ -n "${APP_BINARY_ID}" ]]; then
      cmd+=(--app-binary-id "${APP_BINARY_ID}")
    else
      cmd+=(--app-file "${APK_PATH}")
    fi

    echo "Running ${model_key} on Android API ${api_level}"
    if ! "${cmd[@]}" | tee "${run_dir}/run.log"; then
      EXIT_CODE=1
    fi
  done
done

exit "${EXIT_CODE}"
