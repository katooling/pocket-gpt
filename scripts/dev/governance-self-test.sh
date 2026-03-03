#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

DATE_DIR="$(date +%F)"
RUN_DIR="scripts/benchmarks/runs/${DATE_DIR}/SELFTEST/governance-self-test"
mkdir -p "${RUN_DIR}"

evidence_ok="/tmp/evidence-ok-$$.md"
evidence_bad="/tmp/evidence-bad-$$.md"
pr_ok="/tmp/pr-ok-$$.md"
pr_bad="/tmp/pr-bad-$$.md"
pr_stage_close="/tmp/pr-stage-close-$$.md"

cleanup() {
  rm -f "${evidence_ok}" "${evidence_bad}" "${pr_ok}" "${pr_bad}" "${pr_stage_close}"
}
trap cleanup EXIT

echo "selftest" > "${RUN_DIR}/artifact.txt"

cat > "${evidence_ok}" <<EOM
Evidence note
- raw path: scripts/benchmarks/runs/${DATE_DIR}/SELFTEST/governance-self-test/artifact.txt
EOM

cat > "${evidence_bad}" <<EOM
Evidence note
- raw path: scripts/benchmarks/runs/${DATE_DIR}/SELFTEST/governance-self-test/missing.txt
EOM

bash scripts/dev/evidence-check.sh "${evidence_ok}" >/dev/null

set +e
bash scripts/dev/evidence-check.sh "${evidence_bad}" >/dev/null 2>&1
rc=$?
set -e
if [[ "${rc}" -eq 0 ]]; then
  echo "Expected evidence-check failure did not occur" >&2
  exit 1
fi

cat > "${pr_ok}" <<'EOM'
- [x] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.
- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.
- [x] I updated docs affected by this change, or confirmed no docs changes are needed.
- [x] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` and linked it below.

Stage close: no

Evidence note(s): docs/operations/evidence/wp-03/2026-03-03-eng-ops-foundations.md
EOM

bash scripts/dev/validate-pr-body.sh "${pr_ok}" >/dev/null
bash scripts/dev/stage-close-gate.sh "${pr_ok}" >/dev/null

cat > "${pr_bad}" <<'EOM'
- [ ] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.
- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.
- [x] I updated docs affected by this change, or confirmed no docs changes are needed.
EOM

set +e
bash scripts/dev/validate-pr-body.sh "${pr_bad}" >/dev/null 2>&1
rc=$?
set -e
if [[ "${rc}" -eq 0 ]]; then
  echo "Expected validate-pr-body failure did not occur" >&2
  exit 1
fi

RUN_PATH="scripts/benchmarks/runs/${DATE_DIR}/SELFTEST/governance-self-test/artifact.txt"
{
  echo '- [x] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.'
  echo '- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.'
  echo '- [x] I updated docs affected by this change, or confirmed no docs changes are needed.'
  echo '- [x] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` and linked it below.'
  echo
  echo 'Stage close: yes'
  echo
  echo 'Evidence note(s): docs/operations/evidence/wp-03/2026-03-03-eng-ops-foundations.md'
  echo "Raw run artifacts (\`scripts/benchmarks/runs/...\`): ${RUN_PATH}"
} > "${pr_stage_close}"

bash scripts/dev/validate-pr-body.sh "${pr_stage_close}" >/dev/null
bash scripts/dev/stage-close-gate.sh "${pr_stage_close}" >/dev/null

echo "Governance self-test passed."
