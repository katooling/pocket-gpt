#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT_DIR}"

MODULES=(
  "packages:core-domain"
  "packages:memory"
  "packages:tool-runtime"
  "packages:inference-adapters"
  "packages:native-bridge"
  "packages:app-runtime"
)

FORCE_REBUILD=0
if [[ "${1:-}" == "--force" ]]; then
  FORCE_REBUILD=1
fi

module_dir() {
  local gradle_path="$1"
  echo "${gradle_path//://\/}"
}

missing_outputs=()
if [[ "${FORCE_REBUILD}" -eq 1 ]]; then
  missing_outputs=("${MODULES[@]}")
else
  for module in "${MODULES[@]}"; do
    rel_dir="$(module_dir "${module}")"
    if ! compgen -G "${rel_dir}/build/libs/*.jar" >/dev/null; then
      missing_outputs+=("${module}")
    fi
  done
fi

if [[ "${#missing_outputs[@]}" -eq 0 ]]; then
  exit 0
fi

echo "Repairing missing JVM module outputs: ${missing_outputs[*]}"
tasks=()
for module in "${missing_outputs[@]}"; do
  tasks+=(":${module}:jar")
done

./gradlew --no-daemon --rerun-tasks "${tasks[@]}"
