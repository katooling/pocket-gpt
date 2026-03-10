#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi

: "${MAESTRO_CLOUD_API_KEY:?Set MAESTRO_CLOUD_API_KEY in .env}"
PROJECT_ID="${MAESTRO_PROJECT_ID:-}"
WATCH=0
INTERVAL_SEC=60
UPLOADS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-id)
      PROJECT_ID="${2:?missing project id}"
      shift 2
      ;;
    --watch)
      WATCH=1
      shift
      ;;
    --interval)
      INTERVAL_SEC="${2:?missing interval seconds}"
      shift 2
      ;;
    --help)
      cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-upload-status.sh [--project-id <id>] [--watch] [--interval <sec>] <label:upload-id>...
USAGE
      exit 0
      ;;
    *)
      UPLOADS+=("$1")
      shift
      ;;
  esac
done

if [[ -z "${PROJECT_ID}" ]]; then
  echo "Set --project-id or MAESTRO_PROJECT_ID." >&2
  exit 1
fi

if [[ ${#UPLOADS[@]} -eq 0 ]]; then
  echo "Provide at least one <label:upload-id> entry." >&2
  exit 1
fi

poll_once() {
  printf '%-18s %-28s %-10s %-10s %-8s %-12s %s\n' 'label' 'upload_id' 'upload' 'flow' 'done' 'launched' 'errors'
  for entry in "${UPLOADS[@]}"; do
    label="${entry%%:*}"
    upload_id="${entry##*:}"
    json="$(curl -sS -H "Authorization: Bearer ${MAESTRO_CLOUD_API_KEY}" "https://api.copilot.mobile.dev/v2/project/${PROJECT_ID}/upload/${upload_id}")"
    printf '%-18s %-28s %-10s %-10s %-8s %-12s %s\n' \
      "${label}" \
      "${upload_id}" \
      "$(jq -r '.status' <<<"${json}")" \
      "$(jq -r '.flows[0].status // ""' <<<"${json}")" \
      "$(jq -r '.completed' <<<"${json}")" \
      "$(jq -r '.wasAppLaunched' <<<"${json}")" \
      "$(jq -r '(.flows[0].errors // []) | join(" | ")' <<<"${json}")"
  done
}

if [[ ${WATCH} -eq 0 ]]; then
  poll_once
  exit 0
fi

while true; do
  date '+%Y-%m-%d %H:%M:%S'
  poll_once
  if printf '%s\n' "${UPLOADS[@]}" | while read -r entry; do
    upload_id="${entry##*:}"
    curl -sS -H "Authorization: Bearer ${MAESTRO_CLOUD_API_KEY}" "https://api.copilot.mobile.dev/v2/project/${PROJECT_ID}/upload/${upload_id}" | jq -e '.completed == true' >/dev/null || exit 1
  done; then
    break
  fi
  echo
  sleep "${INTERVAL_SEC}"
done
