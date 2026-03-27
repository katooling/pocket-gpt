#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE' >&2
Usage:
  bash scripts/dev/cpu-sweep.sh --device <serial> [--run-dir <path>] [--install-mode auto|force|skip] \
    [--preset compact|full] [--pocketpal <json-or-csv>] [--model-0-8b-path <device-abs-path>] [-- <extra stage2 args>]

Runs a curated CPU tuning sweep on the 0.8B shared-session stage-2 path, then ranks the variants.
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

DEVICE=""
RUN_DIR=""
INSTALL_MODE="${POCKETGPT_CPU_SWEEP_INSTALL_MODE:-auto}"
PRESET="${POCKETGPT_CPU_SWEEP_PRESET:-compact}"
POCKETPAL_PATH="${POCKETGPT_CPU_SWEEP_POCKETPAL_PATH:-}"
MODEL_0_8B_PATH="${POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH:-}"
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
      shift 2
      ;;
    --run-dir)
      RUN_DIR="${2:-}"
      shift 2
      ;;
    --install-mode)
      INSTALL_MODE="${2:-}"
      shift 2
      ;;
    --preset)
      PRESET="${2:-}"
      shift 2
      ;;
    --pocketpal)
      POCKETPAL_PATH="${2:-}"
      shift 2
      ;;
    --model-0-8b-path)
      MODEL_0_8B_PATH="${2:-}"
      shift 2
      ;;
    --)
      shift
      EXTRA_ARGS+=("$@")
      break
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ -z "${DEVICE}" ]]; then
  echo "Missing required flag: --device" >&2
  usage
  exit 1
fi

if [[ -z "${MODEL_0_8B_PATH}" ]]; then
  echo "Missing required model path: --model-0-8b-path or POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH" >&2
  exit 1
fi

PRESET="$(echo "${PRESET}" | tr '[:upper:]' '[:lower:]')"
if [[ "${PRESET}" != "compact" && "${PRESET}" != "full" ]]; then
  echo "--preset must be compact or full" >&2
  exit 1
fi

if [[ -z "${RUN_DIR}" ]]; then
  RUN_DIR="${REPO_ROOT}/tmp/cpu-sweep-$(date +%Y%m%d-%H%M%S)"
fi

VARIANTS_DIR="${RUN_DIR}/variants"
mkdir -p "${VARIANTS_DIR}"

variant_names=(
  "cpu_default"
  "cpu_flash_auto"
  "cpu_flash_auto_batch128"
)
variant_args=(
  ""
  "--flash-attn auto"
  "--flash-attn auto --n-batch 128 --n-ubatch 128"
)

if [[ "${PRESET}" == "full" ]]; then
  variant_names+=(
    "cpu_flash_auto_batch64"
    "cpu_flash_auto_ctx2048_batch128"
  )
  variant_args+=(
    "--flash-attn auto --n-batch 64 --n-ubatch 64"
    "--flash-attn auto --n-ctx 2048 --n-batch 128 --n-ubatch 128"
  )
fi

for index in "${!variant_names[@]}"; do
  name="${variant_names[$index]}"
  args_string="${variant_args[$index]}"
  run_variant_dir="${VARIANTS_DIR}/${name}"

  cmd=(
    bash "${REPO_ROOT}/scripts/android/run_stage2_native.sh"
    --device "${DEVICE}"
    --run-dir "${run_variant_dir}"
    --profile quick
    --models 0.8b
    --scenarios a
    --session-mode shared
    --disable-tools 1
    --require-prefix-cache-hit 1
    --install-mode "${INSTALL_MODE}"
    --model-0-8b-path "${MODEL_0_8B_PATH}"
  )

  if [[ -n "${args_string}" ]]; then
    # shellcheck disable=SC2206
    parsed_args=(${args_string})
    cmd+=("${parsed_args[@]}")
  fi

  if (( ${#EXTRA_ARGS[@]} > 0 )); then
    cmd+=("${EXTRA_ARGS[@]}")
  fi
  echo "==> Running ${name}"
  "${cmd[@]}"
done

summary_json="${RUN_DIR}/cpu-sweep-summary.json"
summary_csv="${RUN_DIR}/cpu-sweep-summary.csv"
summary_md="${RUN_DIR}/cpu-sweep-summary.md"

rank_cmd=(
  python3 "${REPO_ROOT}/scripts/benchmarks/rank_stage2_sweep.py"
  --runs-root "${VARIANTS_DIR}"
  --scenario A
  --out-json "${summary_json}"
  --out-csv "${summary_csv}"
  --out-md "${summary_md}"
)

if [[ -n "${POCKETPAL_PATH}" ]]; then
  rank_cmd+=(--pocketpal "${POCKETPAL_PATH}")
fi

"${rank_cmd[@]}"

echo "Sweep summary:"
echo "  ${summary_md}"
