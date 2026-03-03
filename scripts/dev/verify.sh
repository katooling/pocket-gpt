#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# Keep Gradle caches inside the repo for deterministic local/CI behavior.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${REPO_ROOT}/.gradle-home}"

./gradlew --no-daemon clean test
