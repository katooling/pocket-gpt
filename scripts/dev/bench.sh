#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 stage2 --device <id> [--date YYYY-MM-DD] [--scenario-a <file>] [--scenario-b <file>]" >&2
}

if [[ "$#" -lt 3 ]]; then
  usage
  exit 1
fi

STAGE="$1"
shift

if [[ "${STAGE}" != "stage2" ]]; then
  echo "Only stage2 is supported." >&2
  exit 1
fi

RUN_DATE="$(date +%F)"
DEVICE_ID=""
SCENARIO_A_SRC=""
SCENARIO_B_SRC=""

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_ID="$2"
      shift 2
      ;;
    --date)
      RUN_DATE="$2"
      shift 2
      ;;
    --scenario-a)
      SCENARIO_A_SRC="$2"
      shift 2
      ;;
    --scenario-b)
      SCENARIO_B_SRC="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${DEVICE_ID}" ]]; then
  usage
  exit 1
fi

RUN_DIR="scripts/benchmarks/runs/${RUN_DATE}/${DEVICE_ID}"
mkdir -p "${RUN_DIR}"

cp docs/operations/evidence/wp-03/templates/notes.md "${RUN_DIR}/notes.md"
cp docs/operations/evidence/wp-03/templates/scenario-a.csv "${RUN_DIR}/scenario-a.csv"
cp docs/operations/evidence/wp-03/templates/scenario-b.csv "${RUN_DIR}/scenario-b.csv"

if [[ -n "${SCENARIO_A_SRC}" ]]; then
  cp "${SCENARIO_A_SRC}" "${RUN_DIR}/scenario-a.csv"
fi
if [[ -n "${SCENARIO_B_SRC}" ]]; then
  cp "${SCENARIO_B_SRC}" "${RUN_DIR}/scenario-b.csv"
fi

head -n 1 "${RUN_DIR}/scenario-a.csv" > "${RUN_DIR}/stage-2-threshold-input.csv"
tail -n +2 "${RUN_DIR}/scenario-a.csv" >> "${RUN_DIR}/stage-2-threshold-input.csv"
tail -n +2 "${RUN_DIR}/scenario-b.csv" >> "${RUN_DIR}/stage-2-threshold-input.csv"

if command -v adb >/dev/null 2>&1; then
  set +e
  adb -s "${DEVICE_ID}" logcat -d > "${RUN_DIR}/logcat.txt"
  adb_exit=$?
  set -e
  if [[ "${adb_exit}" -ne 0 ]]; then
    echo "adb logcat collection failed for ${DEVICE_ID}" > "${RUN_DIR}/logcat.txt"
  fi
else
  echo "adb not found on host" > "${RUN_DIR}/logcat.txt"
fi

python3 scripts/benchmarks/evaluate_thresholds.py "${RUN_DIR}/stage-2-threshold-input.csv" | tee "${RUN_DIR}/threshold-report.txt"
threshold_exit=${PIPESTATUS[0]}

summary_path="${RUN_DIR}/summary.json"
cat > "${summary_path}" <<JSON
{
  "stage": "stage2",
  "device_id": "${DEVICE_ID}",
  "run_date": "${RUN_DATE}",
  "run_dir": "${RUN_DIR}",
  "scenario_a_csv": "${RUN_DIR}/scenario-a.csv",
  "scenario_b_csv": "${RUN_DIR}/scenario-b.csv",
  "threshold_input_csv": "${RUN_DIR}/stage-2-threshold-input.csv",
  "threshold_report": "${RUN_DIR}/threshold-report.txt",
  "logcat": "${RUN_DIR}/logcat.txt",
  "notes": "${RUN_DIR}/notes.md",
  "threshold_exit_code": ${threshold_exit}
}
JSON

echo "Stage-2 benchmark artifacts: ${RUN_DIR}"
echo "Summary JSON: ${summary_path}"
exit "${threshold_exit}"
