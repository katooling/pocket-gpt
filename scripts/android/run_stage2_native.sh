#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE' >&2
Usage:
  bash scripts/android/run_stage2_native.sh --device <serial> [--date YYYY-MM-DD] [--run-dir <path>] [--runs <n>] \
    [--model-0-8b-path <device-abs-path>] [--model-2b-path <device-abs-path>]
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

DEVICE=""
DATE_VALUE="$(date +%F)"
RUN_DIR=""
RUNS=3
MODEL_0_8B_PATH="${POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH:-}"
MODEL_2B_PATH="${POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
      shift 2
      ;;
    --date)
      DATE_VALUE="${2:-}"
      shift 2
      ;;
    --run-dir)
      RUN_DIR="${2:-}"
      shift 2
      ;;
    --runs)
      RUNS="${2:-}"
      shift 2
      ;;
    --model-0-8b-path)
      MODEL_0_8B_PATH="${2:-}"
      shift 2
      ;;
    --model-2b-path)
      MODEL_2B_PATH="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown flag: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${DEVICE}" ]]; then
  echo "Missing required flag: --device" >&2
  usage
  exit 1
fi

if [[ -z "${RUN_DIR}" ]]; then
  RUN_DIR="scripts/benchmarks/runs/${DATE_VALUE}/${DEVICE}"
fi
mkdir -p "${RUN_DIR}"

if ! [[ "${RUNS}" =~ ^[0-9]+$ ]] || [[ "${RUNS}" -le 0 ]]; then
  echo "--runs must be a positive integer" >&2
  exit 1
fi

if [[ -z "${MODEL_0_8B_PATH}" || -z "${MODEL_2B_PATH}" ]]; then
  cat <<'EOF' >&2
Missing model path(s).
Set both env vars or pass explicit flags:
  POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH
  POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH
EOF
  exit 1
fi

MODEL_0_8B_ID="qwen3.5-0.8b-q4"
MODEL_2B_ID="qwen3.5-2b-q4"
PACKAGE_NAME="com.pocketagent.android"
TEST_RUNNER="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.pocketagent.android.NativeStage2BenchmarkInstrumentationTest#runConfiguredScenario"
CSV_HEADER="date,platform,device_class,device_name,backend,runtime,model,scenario,first_token_ms,decode_tps,peak_rss_mb,battery_drop_pct_10m,thermal_note,crash_or_oom"

SCENARIO_A_CSV="${RUN_DIR}/scenario-a.csv"
SCENARIO_B_CSV="${RUN_DIR}/scenario-b.csv"
MODEL_2B_CSV="${RUN_DIR}/model-2b-metrics.csv"
THRESHOLD_INPUT_CSV="${RUN_DIR}/stage-2-threshold-input.csv"
NOTES_PATH="${RUN_DIR}/notes.md"
LOGCAT_PATH="${RUN_DIR}/logcat.txt"

printf '%s\n' "${CSV_HEADER}" > "${SCENARIO_A_CSV}"
printf '%s\n' "${CSV_HEADER}" > "${SCENARIO_B_CSV}"
printf '%s\n' "${CSV_HEADER}" > "${MODEL_2B_CSV}"
: > "${LOGCAT_PATH}"

metric_value() {
  local metric_line="$1"
  local key="$2"
  echo "${metric_line}" | tr '|' '\n' | awk -F= -v wanted="${key}" '$1 == wanted {sub("^[^=]+=", "", $0); print; exit}'
}

extract_pss_kb_from_meminfo() {
  local meminfo_path="$1"
  local pss
  pss="$(awk '
    /TOTAL PSS:/ { print $3; exit }
    /^TOTAL[[:space:]]+[0-9]+/ { print $2; exit }
  ' "${meminfo_path}" 2>/dev/null || true)"
  pss="${pss//[^0-9]/}"
  if [[ -z "${pss}" ]]; then
    pss="0"
  fi
  echo "${pss}"
}

to_mb() {
  local kb="$1"
  awk -v value="${kb}" 'BEGIN { printf "%.2f", value / 1024.0 }'
}

verify_model_path_on_device() {
  local path="$1"
  if ! adb -s "${DEVICE}" shell test -f "${path}" >/dev/null 2>&1; then
    echo "Model file not found on device: ${path}" >&2
    exit 1
  fi
}

collect_signal_flag() {
  local logcat_case="$1"
  local crash_count oom_count anr_count
  crash_count="$(grep -Eic 'FATAL EXCEPTION|Process[[:space:]]+com\.pocketagent\.android[[:space:]]+has died' "${logcat_case}" || true)"
  oom_count="$(grep -Eic 'OutOfMemoryError|low memory killer|oom_adj|Killed process.*com\.pocketagent\.android' "${logcat_case}" || true)"
  anr_count="$(grep -Eic 'ANR in com\.pocketagent\.android|Application Not Responding' "${logcat_case}" || true)"
  if [[ "${crash_count}" -gt 0 || "${oom_count}" -gt 0 || "${anr_count}" -gt 0 ]]; then
    echo "true"
  else
    echo "false"
  fi
}

append_metric_row() {
  local target_csv="$1"
  local scenario="$2"
  local model_id="$3"
  local metric_line="$4"
  local meminfo_case="$5"
  local logcat_case="$6"

  local backend first_token_ms decode_tps pss_kb peak_rss_mb crash_or_oom
  backend="$(metric_value "${metric_line}" "backend")"
  first_token_ms="$(metric_value "${metric_line}" "first_token_ms")"
  decode_tps="$(metric_value "${metric_line}" "decode_tps")"
  pss_kb="$(metric_value "${metric_line}" "pss_kb")"
  if [[ -z "${pss_kb}" || "${pss_kb}" == "0" ]]; then
    pss_kb="$(extract_pss_kb_from_meminfo "${meminfo_case}")"
  fi
  peak_rss_mb="$(to_mb "${pss_kb}")"
  crash_or_oom="$(collect_signal_flag "${logcat_case}")"

  if [[ "${backend}" != "NATIVE_JNI" ]]; then
    echo "Non-native backend detected for ${model_id}/${scenario}: ${backend}" >&2
    exit 1
  fi
  if [[ -z "${first_token_ms}" || -z "${decode_tps}" ]]; then
    echo "Missing metric values for ${model_id}/${scenario}: ${metric_line}" >&2
    exit 1
  fi
  if awk -v ft="${first_token_ms}" -v tps="${decode_tps}" 'BEGIN { exit !(ft > 0 && tps > 0) }'; then
    :
  else
    echo "Invalid metric values for ${model_id}/${scenario}: first_token_ms=${first_token_ms}, decode_tps=${decode_tps}" >&2
    exit 1
  fi

  printf '%s\n' \
"${DATE_VALUE},android,mid,${DEVICE},${backend},llama.cpp-native-jni,${model_id},${scenario},${first_token_ms},${decode_tps},${peak_rss_mb},0,captured,${crash_or_oom}" \
    >> "${target_csv}"
}

run_case() {
  local case_label="$1"
  local model_id="$2"
  local scenario="$3"
  local max_tokens="$4"

  local instrument_output="${RUN_DIR}/instrument-${case_label}.txt"
  local logcat_case="${RUN_DIR}/logcat-${case_label}.txt"
  local meminfo_case="${RUN_DIR}/meminfo-${case_label}.txt"

  adb -s "${DEVICE}" logcat -c
  adb -s "${DEVICE}" shell am instrument -w -r \
    -e class "${TEST_CLASS}" \
    -e stage2_scenario "${scenario}" \
    -e stage2_model_id "${model_id}" \
    -e stage2_model_0_8b_path "${MODEL_0_8B_PATH}" \
    -e stage2_model_2b_path "${MODEL_2B_PATH}" \
    -e stage2_runs "${RUNS}" \
    -e stage2_max_tokens "${max_tokens}" \
    "${TEST_RUNNER}" | tee "${instrument_output}"

  adb -s "${DEVICE}" logcat -d > "${logcat_case}"
  adb -s "${DEVICE}" shell dumpsys meminfo "${PACKAGE_NAME}" > "${meminfo_case}"

  {
    printf '===== %s =====\n' "${case_label}"
    cat "${logcat_case}"
    printf '\n'
  } >> "${LOGCAT_PATH}"

  local metric_line
  metric_line="$(grep -Eo 'STAGE2_METRIC\|[^[:space:]]+' "${instrument_output}" | tail -n 1 || true)"
  if [[ -z "${metric_line}" ]]; then
    metric_line="$(grep -Eo 'STAGE2_METRIC\|[^[:space:]]+' "${logcat_case}" | tail -n 1 || true)"
  fi
  if [[ -z "${metric_line}" ]]; then
    echo "Failed to extract STAGE2_METRIC line for ${case_label}" >&2
    exit 1
  fi

  if [[ "${model_id}" == "${MODEL_0_8B_ID}" && "${scenario}" == "A" ]]; then
    append_metric_row "${SCENARIO_A_CSV}" "${scenario}" "${model_id}" "${metric_line}" "${meminfo_case}" "${logcat_case}"
  elif [[ "${model_id}" == "${MODEL_0_8B_ID}" && "${scenario}" == "B" ]]; then
    append_metric_row "${SCENARIO_B_CSV}" "${scenario}" "${model_id}" "${metric_line}" "${meminfo_case}" "${logcat_case}"
  else
    append_metric_row "${MODEL_2B_CSV}" "${scenario}" "${model_id}" "${metric_line}" "${meminfo_case}" "${logcat_case}"
  fi
}

verify_model_path_on_device "${MODEL_0_8B_PATH}"
verify_model_path_on_device "${MODEL_2B_PATH}"

adb -s "${DEVICE}" get-state >/dev/null

./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true \
  :apps:mobile-android:installDebug \
  :apps:mobile-android:installDebugAndroidTest

run_case "0-8b-scenario-a" "${MODEL_0_8B_ID}" "A" 128
run_case "0-8b-scenario-b" "${MODEL_0_8B_ID}" "B" 256
run_case "2b-scenario-a" "${MODEL_2B_ID}" "A" 128
run_case "2b-scenario-b" "${MODEL_2B_ID}" "B" 256

{
  head -n 1 "${SCENARIO_A_CSV}"
  tail -n +2 "${SCENARIO_A_CSV}"
  tail -n +2 "${SCENARIO_B_CSV}"
} > "${THRESHOLD_INPUT_CSV}"

GIT_SHA="$(git rev-parse --short HEAD)"
{
  echo "# Stage-2 Native Runtime Notes"
  echo
  echo "- Date: ${DATE_VALUE}"
  echo "- Device: ${DEVICE}"
  echo "- Build SHA: ${GIT_SHA}"
  echo "- Runtime: NATIVE_JNI"
  echo "- 0.8B model path: ${MODEL_0_8B_PATH}"
  echo "- 2B model path: ${MODEL_2B_PATH}"
  echo "- Scenario A rows: $(($(wc -l < "${SCENARIO_A_CSV}") - 1))"
  echo "- Scenario B rows: $(($(wc -l < "${SCENARIO_B_CSV}") - 1))"
  echo "- 2B metrics rows: $(($(wc -l < "${MODEL_2B_CSV}") - 1))"
  echo "- Meminfo snapshots:"
  ls -1 "${RUN_DIR}"/meminfo-*.txt | sed 's|^|- |'
} > "${NOTES_PATH}"

echo "Stage-2 native artifacts written to ${RUN_DIR}"
