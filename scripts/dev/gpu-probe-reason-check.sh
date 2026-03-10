#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  bash scripts/dev/gpu-probe-reason-check.sh [options] [-- <extra maestro args>]

Runs a stable GPU probe Maestro flow, then asserts probe_reason from RuntimeGateway
eligibility logs in captured logcat.

Options:
  --serial <id>                  Device serial (default: first attached device)
  --flow <path>                  Maestro flow path (default: tests/maestro/scenario-gpu-probe-status.yaml)
  --expect <REASON[,REASON...]>  Accepted probe_reason values
  --clear-state                  Clear app state before run (pm clear)
  --strict-maestro-exit          Fail when scoped-repro exits nonzero/non-86
  --no-build                     Skip Gradle assemble
  --no-install                   Skip adb install
  --log-dir <path>               Output root (default: tmp)
  --help                         Show help

Examples:
  bash scripts/dev/gpu-probe-reason-check.sh --clear-state --expect MODEL_UNAVAILABLE
  bash scripts/dev/gpu-probe-reason-check.sh --expect PROBE_PROCESS_DIED,MODEL_UNAVAILABLE --no-build --no-install
EOF
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

FLOW_PATH="tests/maestro/scenario-gpu-probe-status.yaml"
DEVICE_SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
EXPECT_RAW="PROBE_PROCESS_DIED,MODEL_UNAVAILABLE,SERVICE_BUSY,PROBE_TIMEOUT,PROBE_BIND_FAILED,RUNTIME_UNSUPPORTED,UNKNOWN"
CLEAR_STATE=0
BUILD_FLAG=1
INSTALL_FLAG=1
STRICT_MAESTRO_EXIT=0
LOG_ROOT="tmp"
MAESTRO_EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      DEVICE_SERIAL="${2:-}"
      shift 2
      ;;
    --flow)
      FLOW_PATH="${2:-}"
      shift 2
      ;;
    --expect)
      EXPECT_RAW="${2:-}"
      shift 2
      ;;
    --clear-state)
      CLEAR_STATE=1
      shift
      ;;
    --no-build)
      BUILD_FLAG=0
      shift
      ;;
    --no-install)
      INSTALL_FLAG=0
      shift
      ;;
    --strict-maestro-exit)
      STRICT_MAESTRO_EXIT=1
      shift
      ;;
    --log-dir)
      LOG_ROOT="${2:-}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      MAESTRO_EXTRA_ARGS=("$@")
      break
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ ! -f "${FLOW_PATH}" ]]; then
  echo "Flow not found: ${FLOW_PATH}" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not installed or not on PATH." >&2
  exit 1
fi

if ! command -v rg >/dev/null 2>&1; then
  echo "rg is not installed or not on PATH." >&2
  exit 1
fi

if [[ -z "${DEVICE_SERIAL}" ]]; then
  DEVICE_SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [[ -z "${DEVICE_SERIAL}" ]]; then
  echo "No connected device detected. Attach a device or pass --serial <id>." >&2
  exit 1
fi

if [[ ${CLEAR_STATE} -eq 1 ]]; then
  adb -s "${DEVICE_SERIAL}" shell pm clear com.pocketagent.android >/dev/null
fi

RUN_DIR="${LOG_ROOT}/gpu-probe-reason-$(date +%Y%m%d-%H%M%S)"
mkdir -p "${RUN_DIR}"

SCOPED_CMD=(bash scripts/dev/scoped-repro.sh --flow "${FLOW_PATH}" --serial "${DEVICE_SERIAL}" --log-dir "${RUN_DIR}")
if [[ ${BUILD_FLAG} -eq 0 ]]; then
  SCOPED_CMD+=(--no-build)
fi
if [[ ${INSTALL_FLAG} -eq 0 ]]; then
  SCOPED_CMD+=(--no-install)
fi
if [[ ${#MAESTRO_EXTRA_ARGS[@]} -gt 0 ]]; then
  SCOPED_CMD+=(-- "${MAESTRO_EXTRA_ARGS[@]}")
fi

set +e
"${SCOPED_CMD[@]}" | tee "${RUN_DIR}/scoped-summary.txt"
SCOPED_EXIT=$?
set -e

LOGCAT_PATH="$(find "${RUN_DIR}" -maxdepth 1 -type f -name '*-logcat.txt' | sort | tail -n 1)"
if [[ -z "${LOGCAT_PATH}" ]]; then
  echo "No logcat artifact found under ${RUN_DIR}" >&2
  exit 1
fi

ELIGIBILITY_LINE="$(rg "RuntimeGateway: GPU_OFFLOAD\\|eligibility\\|.*probe_reason=" "${LOGCAT_PATH}" | tail -n 1 || true)"
if [[ -z "${ELIGIBILITY_LINE}" ]]; then
  echo "No RuntimeGateway eligibility probe_reason line found in ${LOGCAT_PATH}" >&2
  exit 1
fi

PROBE_REASON="$(printf '%s\n' "${ELIGIBILITY_LINE}" | sed -E 's/.*probe_reason=([^| ]+).*/\1/')"
PROBE_STATUS="$(printf '%s\n' "${ELIGIBILITY_LINE}" | sed -E 's/.*probe_status=([^| ]+).*/\1/')"

EXPECTED_MATCH=0
IFS=',' read -r -a EXPECTED_REASONS <<< "${EXPECT_RAW}"
for expected in "${EXPECTED_REASONS[@]}"; do
  normalized="$(echo "${expected}" | xargs)"
  if [[ "${PROBE_REASON}" == "${normalized}" ]]; then
    EXPECTED_MATCH=1
    break
  fi
done

if [[ ${EXPECTED_MATCH} -ne 1 ]]; then
  echo "Unexpected probe_reason: ${PROBE_REASON}" >&2
  echo "Expected one of: ${EXPECT_RAW}" >&2
  echo "Eligibility line: ${ELIGIBILITY_LINE}" >&2
  exit 1
fi

if [[ "${PROBE_REASON}" == "PROBE_PROCESS_DIED" ]]; then
  if ! rg -q "Process com\\.pocketagent\\.android:llama_runtime .* has died|Fatal signal 11 \\(SIGSEGV\\).*llama_runtime" "${LOGCAT_PATH}"; then
    echo "probe_reason=PROBE_PROCESS_DIED but no runtime process death evidence found." >&2
    exit 1
  fi
fi

echo "GPU probe reason check: PASS"
echo "  Device: ${DEVICE_SERIAL}"
echo "  Flow: ${FLOW_PATH}"
echo "  Scoped repro exit: ${SCOPED_EXIT}"
echo "  Probe status: ${PROBE_STATUS}"
echo "  Probe reason: ${PROBE_REASON}"
echo "  Eligibility line: ${ELIGIBILITY_LINE}"
echo "  Artifacts: ${RUN_DIR}"

# scoped-repro returns 86 when crash signatures are detected.
# By default, this checker focuses on log-derived probe_reason truth.
if [[ ${SCOPED_EXIT} -ne 0 && ${SCOPED_EXIT} -ne 86 ]]; then
  if [[ ${STRICT_MAESTRO_EXIT} -eq 1 ]]; then
    echo "Scoped repro failed with unexpected exit code ${SCOPED_EXIT}" >&2
    exit "${SCOPED_EXIT}"
  fi
  echo "Scoped repro exited ${SCOPED_EXIT}; probe_reason validation passed, so reporting success."
fi
