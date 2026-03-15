#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ARTIFACT_ROOT="${POCKET_GPT_DEVCTL_ARTIFACT_ROOT:-${ROOT_DIR}/tmp/devctl-artifacts}"
DAYS=14
INCLUDE_CLOUD=0
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: bash scripts/dev/prune-devctl-artifacts.sh [--days N] [--include-cloud] [--dry-run]

Deletes old local devctl lane artifacts from tmp/devctl-artifacts/.
Optionally also deletes old Maestro Cloud JUnit/report output under tmp/maestro-cloud-*/.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --days)
      DAYS="${2:?missing value for --days}"
      shift 2
      ;;
    --include-cloud)
      INCLUDE_CLOUD=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! [[ "${DAYS}" =~ ^[0-9]+$ ]]; then
  echo "--days must be a non-negative integer" >&2
  exit 1
fi

remove_matches() {
  local label="$1"
  local base_dir="$2"
  local pattern="$3"

  if [[ ! -d "${base_dir}" ]]; then
    echo "Skipping ${label}: ${base_dir} does not exist"
    return
  fi

  local matches=()
  while IFS= read -r match; do
    matches+=("${match}")
  done < <(find "${base_dir}" -mindepth 1 -maxdepth 1 -type d -name "${pattern}" -mtime +"${DAYS}" | sort)
  if [[ ${#matches[@]} -eq 0 ]]; then
    echo "No ${label} directories older than ${DAYS} day(s) under ${base_dir}"
    return
  fi

  for match in "${matches[@]}"; do
    if [[ "${DRY_RUN}" -eq 1 ]]; then
      echo "Would remove ${match}"
    else
      rm -rf "${match}"
      echo "Removed ${match}"
    fi
  done
}

remove_matches "devctl artifact" "${ARTIFACT_ROOT}" "*"

if [[ "${INCLUDE_CLOUD}" -eq 1 ]]; then
  remove_matches "Maestro Cloud artifact" "${ROOT_DIR}/tmp" "maestro-cloud-*"
fi
