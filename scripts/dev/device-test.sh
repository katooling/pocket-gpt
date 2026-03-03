#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

RUNS="${1:-10}"
LABEL="${2:-scenario-a-stage-run}"
shift $(( $# > 0 ? 1 : 0 ))
shift $(( $# > 0 ? 1 : 0 ))

SCENARIO_COMMAND=("./gradlew" "--no-daemon" ":apps:mobile-android-host:run")
if [[ "$#" -gt 0 ]]; then
  if [[ "${1}" == "--" ]]; then
    shift
  fi
  SCENARIO_COMMAND=("$@")
fi

bash scripts/android/run_stage_checks.sh
bash scripts/android/capture_device_baseline.sh
bash scripts/android/configure_device_for_benchmark.sh apply

cleanup() {
  bash scripts/android/configure_device_for_benchmark.sh reset >/dev/null 2>&1 || true
}
trap cleanup EXIT

bash scripts/android/run_short_loop.sh --runs "${RUNS}" --label "${LABEL}" -- "${SCENARIO_COMMAND[@]}"
