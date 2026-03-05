#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE' >&2
Usage:
  bash scripts/android/run_stage2_native.sh --device <serial> [--date YYYY-MM-DD] [--run-dir <path>] \
    [--profile quick|closure] [--models 0.8b|2b|both] [--scenarios a|b|both] [--resume] \
    [--install-mode auto|force|skip] [--logcat filtered|full] [--runs <n>] \
    [--max-tokens-a <n>] [--max-tokens-b <n>] [--model-0-8b-path <device-abs-path>] [--model-2b-path <device-abs-path>]
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

DEVICE=""
DATE_VALUE="$(date +%F)"
RUN_DIR=""
PROFILE="${POCKETGPT_STAGE2_PROFILE:-closure}"
MODELS=""
SCENARIOS="both"
RESUME=0
INSTALL_MODE="auto"
LOGCAT_MODE=""
RUNS="${POCKETGPT_STAGE2_RUNS:-}"
MAX_TOKENS_A="${POCKETGPT_STAGE2_MAX_TOKENS_A:-}"
MAX_TOKENS_B="${POCKETGPT_STAGE2_MAX_TOKENS_B:-}"
MIN_TOKENS="${POCKETGPT_STAGE2_MIN_TOKENS:-}"
WARMUP_MAX_TOKENS="${POCKETGPT_STAGE2_WARMUP_MAX_TOKENS:-}"
MODEL_0_8B_PATH="${POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH:-}"
MODEL_2B_PATH="${POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH:-}"
MODEL_PROVISION_SKIPPED="${POCKETGPT_STAGE2_MODEL_PROVISION_SKIPPED:-unknown}"
PREFIX_CACHE_ENABLED="${POCKETGPT_PREFIX_CACHE_ENABLED:-1}"
PREFIX_CACHE_STRICT="${POCKETGPT_PREFIX_CACHE_STRICT:-0}"

TOTAL_PREFIX_CACHE_HITS=0
TOTAL_PREFIX_CACHE_MISSES=0
TOTAL_PREFILL_REUSED_TOKENS=0
PREFIX_CACHE_LOGS_MISSING=0
WARM_VS_COLD_FIRST_TOKEN_DELTA_MS=""

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
    --profile)
      PROFILE="${2:-}"
      shift 2
      ;;
    --models)
      MODELS="${2:-}"
      shift 2
      ;;
    --scenarios)
      SCENARIOS="${2:-}"
      shift 2
      ;;
    --resume)
      RESUME=1
      shift
      ;;
    --install-mode)
      INSTALL_MODE="${2:-}"
      shift 2
      ;;
    --logcat)
      LOGCAT_MODE="${2:-}"
      shift 2
      ;;
    --runs)
      RUNS="${2:-}"
      shift 2
      ;;
    --max-tokens-a)
      MAX_TOKENS_A="${2:-}"
      shift 2
      ;;
    --max-tokens-b)
      MAX_TOKENS_B="${2:-}"
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

PROFILE="$(echo "${PROFILE}" | tr '[:upper:]' '[:lower:]')"
if [[ "${PROFILE}" != "quick" && "${PROFILE}" != "closure" ]]; then
  echo "--profile must be quick or closure" >&2
  exit 1
fi

if [[ -z "${MODELS}" ]]; then
  if [[ "${PROFILE}" == "quick" ]]; then
    MODELS="0.8b"
  else
    MODELS="both"
  fi
fi
MODELS="$(echo "${MODELS}" | tr '[:upper:]' '[:lower:]')"
if [[ "${MODELS}" != "0.8b" && "${MODELS}" != "2b" && "${MODELS}" != "both" ]]; then
  echo "--models must be 0.8b, 2b, or both" >&2
  exit 1
fi

SCENARIOS="$(echo "${SCENARIOS}" | tr '[:upper:]' '[:lower:]')"
if [[ "${SCENARIOS}" != "a" && "${SCENARIOS}" != "b" && "${SCENARIOS}" != "both" ]]; then
  echo "--scenarios must be a, b, or both" >&2
  exit 1
fi

INSTALL_MODE="$(echo "${INSTALL_MODE}" | tr '[:upper:]' '[:lower:]')"
if [[ "${INSTALL_MODE}" != "auto" && "${INSTALL_MODE}" != "force" && "${INSTALL_MODE}" != "skip" ]]; then
  echo "--install-mode must be auto, force, or skip" >&2
  exit 1
fi

if [[ -n "${POCKETGPT_STAGE2_SKIP_INSTALL:-}" && "${INSTALL_MODE}" == "auto" ]]; then
  if [[ "${POCKETGPT_STAGE2_SKIP_INSTALL}" == "1" ]]; then
    INSTALL_MODE="skip"
  fi
fi

if [[ "${PROFILE}" == "closure" ]]; then
  if [[ "${MODELS}" != "both" || "${SCENARIOS}" != "both" ]]; then
    echo "closure profile requires --models both and --scenarios both" >&2
    exit 1
  fi
fi

if [[ -z "${RUNS}" ]]; then
  RUNS="$([[ "${PROFILE}" == "quick" ]] && echo "2" || echo "3")"
fi
if [[ -z "${MAX_TOKENS_A}" ]]; then
  MAX_TOKENS_A="$([[ "${PROFILE}" == "quick" ]] && echo "4" || echo "128")"
fi
if [[ -z "${MAX_TOKENS_B}" ]]; then
  MAX_TOKENS_B="$([[ "${PROFILE}" == "quick" ]] && echo "4" || echo "256")"
fi
if [[ -z "${MIN_TOKENS}" ]]; then
  MIN_TOKENS="$([[ "${PROFILE}" == "quick" ]] && echo "1" || echo "16")"
fi
if [[ -z "${WARMUP_MAX_TOKENS}" ]]; then
  WARMUP_MAX_TOKENS="$([[ "${PROFILE}" == "quick" ]] && echo "0" || echo "8")"
fi
if [[ -z "${LOGCAT_MODE}" ]]; then
  LOGCAT_MODE="$([[ "${PROFILE}" == "quick" ]] && echo "filtered" || echo "full")"
fi

if [[ -z "${RUN_DIR}" ]]; then
  RUN_DIR="scripts/benchmarks/runs/${DATE_VALUE}/${DEVICE}"
fi
mkdir -p "${RUN_DIR}"

CACHE_DIR="scripts/benchmarks/cache/${DEVICE}"
mkdir -p "${CACHE_DIR}"
MANIFEST_PATH="${RUN_DIR}/stage2-run-manifest.tsv"
RUN_META_PATH="${RUN_DIR}/stage2-run-meta.env"
APK_STATE_PATH="${CACHE_DIR}/apk-install-state.env"

if ! [[ "${RUNS}" =~ ^[0-9]+$ ]] || [[ "${RUNS}" -le 0 ]]; then
  echo "--runs must be a positive integer" >&2
  exit 1
fi
if ! [[ "${MAX_TOKENS_A}" =~ ^[0-9]+$ ]] || [[ "${MAX_TOKENS_A}" -le 0 ]]; then
  echo "--max-tokens-a must be a positive integer" >&2
  exit 1
fi
if ! [[ "${MAX_TOKENS_B}" =~ ^[0-9]+$ ]] || [[ "${MAX_TOKENS_B}" -le 0 ]]; then
  echo "--max-tokens-b must be a positive integer" >&2
  exit 1
fi
if ! [[ "${MIN_TOKENS}" =~ ^[0-9]+$ ]] || [[ "${MIN_TOKENS}" -le 0 ]]; then
  echo "POCKETGPT_STAGE2_MIN_TOKENS must be a positive integer" >&2
  exit 1
fi
if ! [[ "${WARMUP_MAX_TOKENS}" =~ ^[0-9]+$ ]]; then
  echo "POCKETGPT_STAGE2_WARMUP_MAX_TOKENS must be a non-negative integer" >&2
  exit 1
fi
if [[ "${LOGCAT_MODE}" != "filtered" && "${LOGCAT_MODE}" != "full" ]]; then
  echo "--logcat must be filtered or full" >&2
  exit 1
fi
if [[ "${PREFIX_CACHE_ENABLED}" != "0" && "${PREFIX_CACHE_ENABLED}" != "1" ]]; then
  echo "POCKETGPT_PREFIX_CACHE_ENABLED must be 0 or 1" >&2
  exit 1
fi
if [[ "${PREFIX_CACHE_STRICT}" != "0" && "${PREFIX_CACHE_STRICT}" != "1" ]]; then
  echo "POCKETGPT_PREFIX_CACHE_STRICT must be 0 or 1" >&2
  exit 1
fi

if [[ -z "${MODEL_0_8B_PATH}" || -z "${MODEL_2B_PATH}" ]]; then
  cat <<'MSG' >&2
Missing model path(s).
Set both env vars or pass explicit flags:
  POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH
  POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH
MSG
  exit 1
fi

MODEL_0_8B_ID="qwen3.5-0.8b-q4"
MODEL_2B_ID="qwen3.5-2b-q4"
PACKAGE_NAME="com.pocketagent.android"
TEST_RUNNER="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS_SWEEP="com.pocketagent.android.NativeStage2BenchmarkInstrumentationTest#runConfiguredModelSweep"
CSV_HEADER="date,platform,device_class,device_name,backend,runtime,model,scenario,first_token_ms,decode_tps,peak_rss_mb,battery_drop_pct_10m,thermal_note,crash_or_oom"

SCENARIO_A_CSV="${RUN_DIR}/scenario-a.csv"
SCENARIO_B_CSV="${RUN_DIR}/scenario-b.csv"
MODEL_2B_CSV="${RUN_DIR}/model-2b-metrics.csv"
THRESHOLD_INPUT_CSV="${RUN_DIR}/stage-2-threshold-input.csv"
NOTES_PATH="${RUN_DIR}/notes.md"
LOGCAT_PATH="${RUN_DIR}/logcat.txt"

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

compute_host_sha256() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  else
    shasum -a 256 "${path}" | awk '{print $1}'
  fi
}

adb_retry() {
  local attempt=1
  local max_attempts=3
  while true; do
    if adb -s "${DEVICE}" "$@"; then
      return 0
    fi
    if [[ "${attempt}" -ge "${max_attempts}" ]]; then
      return 1
    fi
    echo "adb command failed (attempt ${attempt}/${max_attempts}), retrying..." >&2
    adb start-server >/dev/null 2>&1 || true
    adb -s "${DEVICE}" wait-for-device >/dev/null 2>&1 || true
    sleep 2
    attempt=$((attempt + 1))
  done
}

verify_model_path_on_device() {
  local path="$1"
  if [[ "${path}" == "/data/user/0/${PACKAGE_NAME}/"* || "${path}" == "/data/data/${PACKAGE_NAME}/"* ]]; then
    if ! adb_retry shell run-as "${PACKAGE_NAME}" test -f "${path}" >/dev/null 2>&1; then
      echo "Model file not found in app-private storage: ${path}" >&2
      exit 1
    fi
    return
  fi
  if ! adb_retry shell test -f "${path}" >/dev/null 2>&1; then
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

csv_contains_scenario() {
  local csv_list="$1"
  local scenario="$2"
  [[ ",${csv_list}," == *",${scenario},"* ]]
}

scenario_arg_to_csv() {
  case "$1" in
    a|A) echo "A" ;;
    b|B) echo "B" ;;
    both|BOTH) echo "A,B" ;;
    *) echo "" ;;
  esac
}

pending_scenarios_csv() {
  local requested="$1"
  local completed="$2"
  local pending=()
  local scenario
  for scenario in A B; do
    if csv_contains_scenario "${requested}" "${scenario}" && ! csv_contains_scenario "${completed}" "${scenario}"; then
      pending+=("${scenario}")
    fi
  done
  if [[ "${#pending[@]}" -eq 0 ]]; then
    echo ""
  else
    (IFS=','; echo "${pending[*]}")
  fi
}

manifest_get_scenarios() {
  local model_id="$1"
  if [[ ! -f "${MANIFEST_PATH}" ]]; then
    echo ""
    return
  fi
  awk -F'\t' -v id="${model_id}" '$1 == id {print $2; exit}' "${MANIFEST_PATH}"
}

manifest_update_scenarios() {
  local model_id="$1"
  local new_scenarios="$2"
  local existing
  existing="$(manifest_get_scenarios "${model_id}")"
  local merged=()
  local scenario
  for scenario in A B; do
    if csv_contains_scenario "${existing}" "${scenario}" || csv_contains_scenario "${new_scenarios}" "${scenario}"; then
      merged+=("${scenario}")
    fi
  done
  local merged_csv
  if [[ "${#merged[@]}" -eq 0 ]]; then
    merged_csv=""
  else
    (IFS=','; merged_csv="${merged[*]}")
  fi

  local temp_file
  temp_file="$(mktemp)"
  if [[ -f "${MANIFEST_PATH}" ]]; then
    awk -F'\t' -v id="${model_id}" '$1 != id {print $1"\t"$2}' "${MANIFEST_PATH}" > "${temp_file}"
  fi
  if [[ -n "${merged_csv}" ]]; then
    printf '%s\t%s\n' "${model_id}" "${merged_csv}" >> "${temp_file}"
  fi
  mv "${temp_file}" "${MANIFEST_PATH}"
}

ensure_csv_header() {
  local csv_path="$1"
  if [[ ! -f "${csv_path}" ]]; then
    printf '%s\n' "${CSV_HEADER}" > "${csv_path}"
    return
  fi
  local first_line
  first_line="$(head -n 1 "${csv_path}" || true)"
  if [[ "${first_line}" != "${CSV_HEADER}" ]]; then
    printf '%s\n' "${CSV_HEADER}" > "${csv_path}"
  fi
}

reset_csv_with_header() {
  local csv_path="$1"
  printf '%s\n' "${CSV_HEADER}" > "${csv_path}"
}

filter_model_2b_rows() {
  local scenarios_to_replace="$1"
  ensure_csv_header "${MODEL_2B_CSV}"
  local temp_file
  temp_file="$(mktemp)"
  python3 - "${MODEL_2B_CSV}" "${temp_file}" "${scenarios_to_replace}" <<'PY'
import csv
import sys

source, target, scenarios_raw = sys.argv[1:4]
replace = {s.strip().upper() for s in scenarios_raw.split(',') if s.strip()}

with open(source, newline='', encoding='utf-8') as handle:
    reader = list(csv.reader(handle))
if not reader:
    sys.exit(0)
header = reader[0]
scenario_idx = header.index('scenario') if 'scenario' in header else None

with open(target, 'w', newline='', encoding='utf-8') as handle:
    writer = csv.writer(handle)
    writer.writerow(header)
    for row in reader[1:]:
      if not row:
          continue
      scenario = row[scenario_idx].strip().upper() if scenario_idx is not None and scenario_idx < len(row) else ''
      if scenario in replace:
          continue
      writer.writerow(row)
PY
  mv "${temp_file}" "${MODEL_2B_CSV}"
}

prepare_outputs_for_run() {
  local model_id="$1"
  local scenarios_csv="$2"

  ensure_csv_header "${SCENARIO_A_CSV}"
  ensure_csv_header "${SCENARIO_B_CSV}"
  ensure_csv_header "${MODEL_2B_CSV}"

  if [[ "${model_id}" == "${MODEL_0_8B_ID}" ]]; then
    if csv_contains_scenario "${scenarios_csv}" "A"; then
      reset_csv_with_header "${SCENARIO_A_CSV}"
      rm -f "${RUN_DIR}/meminfo-0-8b-scenario-a.txt"
    fi
    if csv_contains_scenario "${scenarios_csv}" "B"; then
      reset_csv_with_header "${SCENARIO_B_CSV}"
      rm -f "${RUN_DIR}/meminfo-0-8b-scenario-b.txt"
    fi
    return
  fi

  if [[ "${scenarios_csv}" == "A,B" ]]; then
    reset_csv_with_header "${MODEL_2B_CSV}"
  else
    filter_model_2b_rows "${scenarios_csv}"
  fi
  if csv_contains_scenario "${scenarios_csv}" "A"; then
    rm -f "${RUN_DIR}/meminfo-2b-scenario-a.txt"
  fi
  if csv_contains_scenario "${scenarios_csv}" "B"; then
    rm -f "${RUN_DIR}/meminfo-2b-scenario-b.txt"
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
  if ! awk -v ft="${first_token_ms}" -v tps="${decode_tps}" 'BEGIN { exit !(ft > 0 && tps > 0) }'; then
    echo "Invalid metric values for ${model_id}/${scenario}: first_token_ms=${first_token_ms}, decode_tps=${decode_tps}" >&2
    exit 1
  fi

  printf '%s\n' \
"${DATE_VALUE},android,mid,${DEVICE},${backend},llama.cpp-native-jni,${model_id},${scenario},${first_token_ms},${decode_tps},${peak_rss_mb},0,captured,${crash_or_oom}" \
    >> "${target_csv}"
}

append_logcat_block() {
  local sweep_label="$1"
  local logcat_case="$2"

  if [[ "${LOGCAT_MODE}" == "filtered" ]]; then
    {
      printf '===== %s =====\n' "${sweep_label}"
      grep -E 'STAGE2_METRIC|PREFIX_CACHE|NATIVE_JNI|ADB_FALLBACK|FATAL EXCEPTION|OutOfMemoryError|ANR in com\.pocketagent\.android|Application Not Responding' "${logcat_case}" || true
      printf '\n'
    } >> "${LOGCAT_PATH}"
    return
  fi

  {
    printf '===== %s =====\n' "${sweep_label}"
    cat "${logcat_case}"
    printf '\n'
  } >> "${LOGCAT_PATH}"
}

collect_prefix_cache_stats() {
  local logcat_case="$1"
  python3 - "${logcat_case}" <<'PY'
import re
import sys

path = sys.argv[1]
hits = 0
misses = 0
reused_tokens = 0
missing = 1
pattern = re.compile(r'PREFIX_CACHE\|([^\r\n]+)')

with open(path, "r", encoding="utf-8", errors="replace") as handle:
    for line in handle:
        match = pattern.search(line)
        if not match:
            continue
        missing = 0
        fields = {}
        for part in match.group(1).split("|"):
            if "=" not in part:
                continue
            key, value = part.split("=", 1)
            fields[key.strip()] = value.strip()
        hit_value = fields.get("hit", "").lower()
        if hit_value in {"1", "true", "yes"}:
            hits += 1
        else:
            misses += 1
        try:
            reused_tokens += int(fields.get("reused_tokens", "0"))
        except ValueError:
            pass

print(f"{hits} {misses} {reused_tokens} {missing}")
PY
}

run_model_sweep() {
  local sweep_label="$1"
  local model_id="$2"
  local scenarios_csv="$3"

  local instrument_output="${RUN_DIR}/instrument-${sweep_label}.txt"
  local logcat_case="${RUN_DIR}/logcat-${sweep_label}.txt"
  local meminfo_case="${RUN_DIR}/meminfo-${sweep_label}.txt"

  adb_retry logcat -c
  adb_retry shell am instrument -w -r \
    -e class "${TEST_CLASS_SWEEP}" \
    -e stage2_model_id "${model_id}" \
    -e stage2_scenarios "${scenarios_csv}" \
    -e stage2_model_0_8b_path "${MODEL_0_8B_PATH}" \
    -e stage2_model_2b_path "${MODEL_2B_PATH}" \
    -e stage2_runs "${RUNS}" \
    -e stage2_max_tokens_a "${MAX_TOKENS_A}" \
    -e stage2_max_tokens_b "${MAX_TOKENS_B}" \
    -e stage2_min_tokens "${MIN_TOKENS}" \
    -e stage2_warmup_max_tokens "${WARMUP_MAX_TOKENS}" \
    -e stage2_prefix_cache_enabled "${PREFIX_CACHE_ENABLED}" \
    -e stage2_prefix_cache_strict "${PREFIX_CACHE_STRICT}" \
    "${TEST_RUNNER}" | tee "${instrument_output}"

  adb_retry logcat -d > "${logcat_case}"
  adb_retry shell dumpsys meminfo "${PACKAGE_NAME}" > "${meminfo_case}"
  append_logcat_block "${sweep_label}" "${logcat_case}"

  local cache_hits cache_misses cache_reused cache_missing
  read -r cache_hits cache_misses cache_reused cache_missing < <(collect_prefix_cache_stats "${logcat_case}")
  TOTAL_PREFIX_CACHE_HITS=$((TOTAL_PREFIX_CACHE_HITS + cache_hits))
  TOTAL_PREFIX_CACHE_MISSES=$((TOTAL_PREFIX_CACHE_MISSES + cache_misses))
  TOTAL_PREFILL_REUSED_TOKENS=$((TOTAL_PREFILL_REUSED_TOKENS + cache_reused))
  if [[ "${cache_missing}" -eq 1 ]]; then
    PREFIX_CACHE_LOGS_MISSING=1
  fi
  if [[ "${PROFILE}" == "quick" && "${PREFIX_CACHE_ENABLED}" == "1" && "${cache_missing}" -eq 1 ]]; then
    echo "Prefix-cache counters missing from logcat for ${sweep_label} while cache is enabled." >&2
    exit 1
  fi

  local metric_lines=()
  local metric_lines_raw
  metric_lines_raw="$(
    {
      grep -Eo 'STAGE2_METRIC\|[^[:space:]]+' "${instrument_output}" || true
      grep -Eo 'STAGE2_METRIC\|[^[:space:]]+' "${logcat_case}" || true
    } | awk 'NF && !seen[$0]++'
  )"
  while IFS= read -r line; do
    if [[ -n "${line}" ]]; then
      metric_lines+=("${line}")
    fi
  done <<< "${metric_lines_raw}"
  if [[ "${#metric_lines[@]}" -eq 0 ]]; then
    echo "Failed to extract STAGE2_METRIC lines for ${sweep_label}" >&2
    exit 1
  fi

  local saw_a=0
  local saw_b=0
  local metric_line scenario parsed_model
  for metric_line in "${metric_lines[@]}"; do
    scenario="$(metric_value "${metric_line}" "scenario")"
    parsed_model="$(metric_value "${metric_line}" "model_id")"
    if [[ "${parsed_model}" != "${model_id}" ]]; then
      continue
    fi
    case "${scenario}" in
      A) saw_a=1 ;;
      B) saw_b=1 ;;
      *) continue ;;
    esac

    local warm_delta
    warm_delta="$(metric_value "${metric_line}" "warm_vs_cold_first_token_delta_ms")"
    if [[ "${model_id}" == "${MODEL_0_8B_ID}" && "${scenario}" == "A" && -n "${warm_delta}" ]]; then
      WARM_VS_COLD_FIRST_TOKEN_DELTA_MS="${warm_delta}"
    fi

    if [[ "${model_id}" == "${MODEL_0_8B_ID}" ]]; then
      if [[ "${scenario}" == "A" ]]; then
        append_metric_row "${SCENARIO_A_CSV}" "${scenario}" "${model_id}" "${metric_line}" "${meminfo_case}" "${logcat_case}"
      else
        append_metric_row "${SCENARIO_B_CSV}" "${scenario}" "${model_id}" "${metric_line}" "${meminfo_case}" "${logcat_case}"
      fi
    else
      append_metric_row "${MODEL_2B_CSV}" "${scenario}" "${model_id}" "${metric_line}" "${meminfo_case}" "${logcat_case}"
    fi
  done

  if csv_contains_scenario "${scenarios_csv}" "A" && [[ "${saw_a}" -ne 1 ]]; then
    echo "Missing scenario A metric in ${sweep_label}" >&2
    exit 1
  fi
  if csv_contains_scenario "${scenarios_csv}" "B" && [[ "${saw_b}" -ne 1 ]]; then
    echo "Missing scenario B metric in ${sweep_label}" >&2
    exit 1
  fi

  if [[ "${model_id}" == "${MODEL_0_8B_ID}" ]]; then
    if csv_contains_scenario "${scenarios_csv}" "A"; then
      cp "${meminfo_case}" "${RUN_DIR}/meminfo-0-8b-scenario-a.txt"
    fi
    if csv_contains_scenario "${scenarios_csv}" "B"; then
      cp "${meminfo_case}" "${RUN_DIR}/meminfo-0-8b-scenario-b.txt"
    fi
  else
    if csv_contains_scenario "${scenarios_csv}" "A"; then
      cp "${meminfo_case}" "${RUN_DIR}/meminfo-2b-scenario-a.txt"
    fi
    if csv_contains_scenario "${scenarios_csv}" "B"; then
      cp "${meminfo_case}" "${RUN_DIR}/meminfo-2b-scenario-b.txt"
    fi
  fi
}

resolve_apk_path() {
  local pattern="$1"
  find apps/mobile-android/build/outputs/apk -type f -name "${pattern}" | sort
}

install_app_if_needed() {
  local apk_install_skipped=0

  if [[ "${INSTALL_MODE}" == "skip" ]]; then
    echo "Skipping installDebug/installDebugAndroidTest (--install-mode skip)" >&2
    apk_install_skipped=1
    echo "${apk_install_skipped}"
    return
  fi

  ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true \
    :apps:mobile-android:assembleDebug \
    :apps:mobile-android:assembleDebugAndroidTest

  local app_apk
  local test_apk
  app_apk="$(resolve_apk_path '*debug*.apk' | grep -v 'androidTest' | head -n 1 || true)"
  test_apk="$(find apps/mobile-android/build/outputs/apk -type f -name '*androidTest*.apk' | sort | head -n 1 || true)"

  if [[ -z "${app_apk}" || -z "${test_apk}" ]]; then
    echo "Failed to locate built debug APK artifacts for install caching." >&2
    exit 1
  fi

  local app_sha
  local test_sha
  app_sha="$(compute_host_sha256 "${app_apk}")"
  test_sha="$(compute_host_sha256 "${test_apk}")"

  local cached_app_sha=""
  local cached_test_sha=""
  if [[ -f "${APK_STATE_PATH}" ]]; then
    # shellcheck disable=SC1090
    source "${APK_STATE_PATH}"
    cached_app_sha="${APP_APK_SHA:-}"
    cached_test_sha="${TEST_APK_SHA:-}"
  fi

  if [[ "${INSTALL_MODE}" == "auto" && "${app_sha}" == "${cached_app_sha}" && "${test_sha}" == "${cached_test_sha}" ]]; then
    echo "Skipping installDebug/installDebugAndroidTest (APK hashes unchanged)." >&2
    apk_install_skipped=1
    echo "${apk_install_skipped}"
    return
  fi

  ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true \
    :apps:mobile-android:installDebug \
    :apps:mobile-android:installDebugAndroidTest

  cat > "${APK_STATE_PATH}" <<STATE
APP_APK_SHA=${app_sha}
TEST_APK_SHA=${test_sha}
APP_APK_PATH=${app_apk}
TEST_APK_PATH=${test_apk}
UPDATED_AT=$(date +%FT%T)
STATE

  echo "${apk_install_skipped}"
}

verify_model_path_on_device "${MODEL_0_8B_PATH}"
verify_model_path_on_device "${MODEL_2B_PATH}"

adb_retry get-state >/dev/null

if [[ "${RESUME}" -ne 1 ]]; then
  rm -f "${MANIFEST_PATH}"
fi

SELECTED_SCENARIOS_CSV="$(scenario_arg_to_csv "${SCENARIOS}")"
if [[ -z "${SELECTED_SCENARIOS_CSV}" ]]; then
  echo "Failed to resolve scenarios from --scenarios ${SCENARIOS}" >&2
  exit 1
fi

if [[ "${RESUME}" -eq 0 && "${MODELS}" == "both" && "${SCENARIOS}" == "both" ]]; then
  find "${RUN_DIR}" -maxdepth 1 -type f \
    \( -name 'instrument-*.txt' -o -name 'logcat-*.txt' -o -name 'meminfo-*.txt' \
       -o -name 'scenario-a.csv' -o -name 'scenario-b.csv' -o -name 'model-2b-metrics.csv' \
       -o -name 'stage-2-threshold-input.csv' -o -name 'threshold-report.txt' \
       -o -name 'runtime-evidence-validation.txt' -o -name 'summary.json' \
       -o -name 'notes.md' -o -name 'logcat.txt' -o -name 'stage2-run-meta.env' \
       -o -name 'stage2-run-manifest.tsv' -o -name 'evidence-draft.md' \) \
    -delete
fi

ensure_csv_header "${SCENARIO_A_CSV}"
ensure_csv_header "${SCENARIO_B_CSV}"
ensure_csv_header "${MODEL_2B_CSV}"
: > "${LOGCAT_PATH}"

APK_INSTALL_SKIPPED="$(install_app_if_needed)"

run_for_model_if_needed() {
  local model_id="$1"
  local sweep_label_base="$2"

  local completed
  completed="$(manifest_get_scenarios "${model_id}")"
  local to_run
  if [[ "${RESUME}" -eq 1 ]]; then
    to_run="$(pending_scenarios_csv "${SELECTED_SCENARIOS_CSV}" "${completed}")"
  else
    to_run="${SELECTED_SCENARIOS_CSV}"
  fi

  if [[ -z "${to_run}" ]]; then
    echo "Skipping ${model_id}: requested scenarios already completed (resume mode)."
    return
  fi

  prepare_outputs_for_run "${model_id}" "${to_run}"
  local suffix
  suffix="$(echo "${to_run}" | tr ',' '-' | tr '[:upper:]' '[:lower:]')"
  run_model_sweep "${sweep_label_base}-${suffix}" "${model_id}" "${to_run}"
  manifest_update_scenarios "${model_id}" "${to_run}"
}

if [[ "${MODELS}" == "0.8b" || "${MODELS}" == "both" ]]; then
  run_for_model_if_needed "${MODEL_0_8B_ID}" "0-8b-sweep"
fi
if [[ "${MODELS}" == "2b" || "${MODELS}" == "both" ]]; then
  run_for_model_if_needed "${MODEL_2B_ID}" "2b-sweep"
fi

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
  echo "- Profile: ${PROFILE}"
  echo "- Selected models: ${MODELS}"
  echo "- Selected scenarios: ${SCENARIOS}"
  echo "- Resume mode: ${RESUME}"
  echo "- Install mode: ${INSTALL_MODE}"
  echo "- APK install skipped: ${APK_INSTALL_SKIPPED}"
  echo "- Runtime: NATIVE_JNI"
  echo "- Prefix cache enabled: ${PREFIX_CACHE_ENABLED}"
  echo "- Prefix cache strict: ${PREFIX_CACHE_STRICT}"
  echo "- Prefix cache hits: ${TOTAL_PREFIX_CACHE_HITS}"
  echo "- Prefix cache misses: ${TOTAL_PREFIX_CACHE_MISSES}"
  echo "- Prefill tokens reused: ${TOTAL_PREFILL_REUSED_TOKENS}"
  echo "- Prefix cache logs missing: ${PREFIX_CACHE_LOGS_MISSING}"
  echo "- Warm-vs-cold first token delta ms: ${WARM_VS_COLD_FIRST_TOKEN_DELTA_MS:-n/a}"
  echo "- Logcat mode: ${LOGCAT_MODE}"
  echo "- 0.8B model path: ${MODEL_0_8B_PATH}"
  echo "- 2B model path: ${MODEL_2B_PATH}"
  echo "- Runs per scenario: ${RUNS}"
  echo "- Max tokens A/B: ${MAX_TOKENS_A}/${MAX_TOKENS_B}"
  echo "- Min tokens override: ${MIN_TOKENS}"
  echo "- Warmup max tokens: ${WARMUP_MAX_TOKENS}"
  echo "- Scenario A rows: $(($(wc -l < "${SCENARIO_A_CSV}") - 1))"
  echo "- Scenario B rows: $(($(wc -l < "${SCENARIO_B_CSV}") - 1))"
  echo "- 2B metrics rows: $(($(wc -l < "${MODEL_2B_CSV}") - 1))"
  echo "- Meminfo snapshots:"
  ls -1 "${RUN_DIR}"/meminfo-*.txt 2>/dev/null | sed 's|^|- |' || echo "- (none)"
} > "${NOTES_PATH}"

STRICT_THRESHOLDS="${POCKETGPT_STAGE2_STRICT_THRESHOLDS:-0}"
MODEL_LOAD_MODE="warm_within_sweep"
cat > "${RUN_META_PATH}" <<META
STAGE2_PROFILE=${PROFILE}
STAGE2_MODELS=${MODELS}
STAGE2_SCENARIOS=${SCENARIOS}
STAGE2_RESUME_USED=${RESUME}
STAGE2_INSTALL_MODE=${INSTALL_MODE}
STAGE2_APK_INSTALL_SKIPPED=${APK_INSTALL_SKIPPED}
STAGE2_MODEL_PROVISION_SKIPPED=${MODEL_PROVISION_SKIPPED}
STAGE2_STRICT_THRESHOLDS=${STRICT_THRESHOLDS}
STAGE2_MODEL_LOAD_MODE=${MODEL_LOAD_MODE}
STAGE2_PREFIX_CACHE_ENABLED=${PREFIX_CACHE_ENABLED}
STAGE2_PREFIX_CACHE_STRICT=${PREFIX_CACHE_STRICT}
STAGE2_PREFIX_CACHE_HITS=${TOTAL_PREFIX_CACHE_HITS}
STAGE2_PREFIX_CACHE_MISSES=${TOTAL_PREFIX_CACHE_MISSES}
STAGE2_PREFILL_TOKENS_REUSED=${TOTAL_PREFILL_REUSED_TOKENS}
STAGE2_PREFIX_CACHE_LOGS_MISSING=${PREFIX_CACHE_LOGS_MISSING}
STAGE2_WARM_VS_COLD_FIRST_TOKEN_DELTA_MS=${WARM_VS_COLD_FIRST_TOKEN_DELTA_MS:-}
META

echo "Stage-2 native artifacts written to ${RUN_DIR}"
