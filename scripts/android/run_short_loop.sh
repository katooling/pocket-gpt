#!/usr/bin/env bash
set -euo pipefail

has_rg() {
  command -v rg >/dev/null 2>&1
}

matches_pattern() {
  local pattern="$1"
  local file="$2"
  if has_rg; then
    rg -q "${pattern}" "${file}"
  else
    grep -Eq "${pattern}" "${file}"
  fi
}

if [[ "$#" -lt 5 ]]; then
  echo "Usage: $0 --runs <n> --label <label> -- <command...>" >&2
  exit 1
fi

RUNS=10
LABEL="short-run"

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --runs)
      RUNS="$2"
      shift 2
      ;;
    --label)
      LABEL="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "$#" -eq 0 ]]; then
  echo "Missing command after '--'." >&2
  exit 1
fi

SERIAL="$(bash "$(dirname "${BASH_SOURCE[0]}")/ensure_device.sh")"
STAMP="$(date +%Y%m%d-%H%M%S)"
DATE_DIR="$(date +%F)"
OUT_DIR="scripts/benchmarks/runs/${DATE_DIR}/${SERIAL}/${LABEL}-${STAMP}"
SUMMARY="${OUT_DIR}/summary.csv"
COMBINED_LOGCAT="${OUT_DIR}/combined-logcat.txt"

mkdir -p "${OUT_DIR}"
printf "run,exit_code,crash_detected,oom_detected,log_file,command_output\n" > "${SUMMARY}"
: > "${COMBINED_LOGCAT}"

echo "Running ${RUNS} loops on device ${SERIAL}"
echo "Output directory: ${OUT_DIR}"
echo "Command: $*"

for ((i=1; i<=RUNS; i++)); do
  RUN_LOG="${OUT_DIR}/run-${i}.log"
  LOGCAT_FILE="${OUT_DIR}/run-${i}-logcat.txt"
  echo "=== Run ${i}/${RUNS} ==="
  adb -s "${SERIAL}" logcat -c

  set +e
  "$@" > "${RUN_LOG}" 2>&1
  EXIT_CODE=$?
  set -e

  adb -s "${SERIAL}" logcat -d > "${LOGCAT_FILE}"
  cat "${LOGCAT_FILE}" >> "${COMBINED_LOGCAT}"

  CRASH_DETECTED="false"
  OOM_DETECTED="false"
  if matches_pattern "FATAL EXCEPTION|ANR in|Fatal signal|Process .* has died" "${LOGCAT_FILE}"; then
    CRASH_DETECTED="true"
  fi
  if matches_pattern "OutOfMemoryError|lowmemorykiller|LMKD" "${LOGCAT_FILE}"; then
    OOM_DETECTED="true"
  fi

  printf "%s,%s,%s,%s,%s,%s\n" \
    "${i}" \
    "${EXIT_CODE}" \
    "${CRASH_DETECTED}" \
    "${OOM_DETECTED}" \
    "${LOGCAT_FILE}" \
    "${RUN_LOG}" \
    >> "${SUMMARY}"
done

echo "Run summary written to ${SUMMARY}"
