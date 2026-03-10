#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

source scripts/dev/maestro-gpu-matrix-common.sh

BUILD_APK=1
INSTALL_APK=1
DRY_RUN=0
SERIALS=()
MODEL_KEYS=(tiny qwen_0_8b qwen_2b)
FLOW_TEMPLATE="tests/maestro/shared/scenario-gpu-qualify-by-model.template.yaml"
OUTPUT_ROOT="tmp/maestro-gpu-real-device-matrix"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIALS+=("${2:?missing serial}")
      shift 2
      ;;
    --models)
      IFS=',' read -r -a MODEL_KEYS <<< "${2:?missing model keys}"
      shift 2
      ;;
    --no-build)
      BUILD_APK=0
      shift
      ;;
    --no-install)
      INSTALL_APK=0
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --help)
      cat <<'USAGE'
Usage: bash scripts/dev/maestro-gpu-real-device-matrix.sh --serial <adb-serial> [--serial <adb-serial> ...] [--models tiny,qwen_0_8b,qwen_2b] [--no-build] [--no-install] [--dry-run]
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ ${#SERIALS[@]} -eq 0 ]]; then
  echo "Provide at least one explicit --serial. This script never auto-picks a device." >&2
  exit 1
fi

if [[ ${BUILD_APK} -eq 1 && ${DRY_RUN} -eq 0 ]]; then
  GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
fi

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' 2>/dev/null | sort | head -n 1)"
if [[ ${DRY_RUN} -eq 0 && ( -z "${APK_PATH}" || ! -f "${APK_PATH}" ) ]]; then
  echo "Debug APK not found." >&2
  exit 1
fi

mkdir -p "${OUTPUT_ROOT}" tmp/maestro-generated
EXIT_CODE=0

for serial in "${SERIALS[@]}"; do
  serial_tag="$(pocketgpt_gpu_matrix_sanitize "${serial}")"
  if [[ ${DRY_RUN} -eq 0 ]]; then
    adb -s "${serial}" get-state >/dev/null
    if [[ ${INSTALL_APK} -eq 1 ]]; then
      adb -s "${serial}" install -r "${APK_PATH}" >/dev/null
    fi
  fi
  for model_key in "${MODEL_KEYS[@]}"; do
    spec="$(pocketgpt_gpu_matrix_model_spec "${model_key}")"
    model_id="${spec%%|*}"
    version="${spec##*|}"
    run_tag="${serial_tag}-${model_key}"
    run_dir="${OUTPUT_ROOT}/${run_tag}"
    flow_path="tmp/maestro-generated/${run_tag}.yaml"
    mkdir -p "${run_dir}"
    pocketgpt_gpu_matrix_make_flow "${flow_path}" "${FLOW_TEMPLATE}" "${model_id}" "${version}" "${run_tag}"

    cmd=(maestro --device "${serial}" test "${flow_path}")
    echo "Running ${model_key} on ${serial}"
    if [[ ${DRY_RUN} -eq 1 ]]; then
      printf 'DRY_RUN '
      printf '%q ' "${cmd[@]}"
      printf '\n'
      continue
    fi

    adb -s "${serial}" logcat -c
    (adb -s "${serial}" logcat -v threadtime > "${run_dir}/logcat.txt") &
    logcat_pid=$!
    set +e
    "${cmd[@]}" | tee "${run_dir}/maestro.log"
    run_exit=$?
    set -e
    kill "${logcat_pid}" >/dev/null 2>&1 || true
    wait "${logcat_pid}" 2>/dev/null || true
    if [[ ${run_exit} -ne 0 ]]; then
      EXIT_CODE=1
    fi
  done
done

exit "${EXIT_CODE}"
