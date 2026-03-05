# PM Owner Briefs (Actionable Next Work)

Date: 2026-03-03
Owner: PM/ProdEng
Scope: Assignment-ready briefs for immediate execution after reconciliation.

Reference raw artifact root used for this dispatch:
`scripts/benchmarks/runs/2026-03-03/DEVICE_SERIAL_REDACTED/qa-02-phase-b-20260303-203909/`

## Brief 1 - Runtime Eng (ENG-04 Closeout)

Goal:
1. Close artifact-path ambiguity for WP-03 Stage-2 closure.

In scope:
1. Remove placeholder checksum usage from active Stage-2 runtime artifact path.
2. Add startup/runtime guard so invalid artifact metadata blocks Stage-2 closure claims.
3. Update tests for checksum-validation and failure-path behavior.

Out of scope:
1. New runtime architecture changes.
2. Non-Stage-2 features.

Commands required:
1. `./gradlew --no-daemon :packages:inference-adapters:test`
2. `bash scripts/dev/test.sh quick`

Evidence required:
1. `docs/operations/evidence/wp-03/YYYY-MM-DD-eng-04-closeout.md`
2. Link raw run artifacts when any device check is part of closeout.

Acceptance:
1. No placeholder checksum in active Stage-2 path.
2. Tests green for checksum pass/fail/unknown.
3. Board + engineering playbook status updated.

## Brief 2 - QA (QA-02 Closeout Refresh)

Goal:
1. Produce final Stage-2 benchmark packet after ENG-04 closeout.

In scope:
1. Re-run Scenario A/B on physical device.
2. Generate final threshold report/logcat.

Commands required:
1. `bash scripts/android/ensure_device.sh`
2. `bash scripts/android/run_stage_checks.sh`
3. `bash scripts/dev/bench.sh stage2 --device <id> --date <YYYY-MM-DD>`

Evidence required:
1. `docs/operations/evidence/wp-03/YYYY-MM-DD-qa-02-closeout.md`
2. Raw artifacts under benchmark run root.

Acceptance:
1. A/B threshold PASS in closure packet.
2. No template/mock data in final evidence.
3. Board + QA playbook status updated.

## Brief 3 - CI/Release Owner

Goal:
1. Ensure WP-03 closure enforcement is deterministic in CI metadata checks.

In scope:
1. Keep governance gates strict for stage-close PRs.
2. Ensure changed evidence notes are validated against existing raw paths.

Commands required:
1. `bash scripts/dev/docs-drift-check.sh`
2. `bash scripts/dev/governance-self-test.sh`
3. `bash scripts/dev/evidence-check.sh <evidence-note.md>`

Acceptance:
1. Governance jobs fail on invalid PR body/evidence refs.
2. No drift in canonical docs contract.

## Brief 4 - Product Lead (PROD-01 / PROD-02 Activation)

Goal:
1. Prepare launch decision inputs that become active immediately after WP-03 is marked Done.

In scope:
1. Lock top 2-3 launch workflows.
2. Define required vs best-effort device policy.

Dependencies:
1. WP-03 closure with final QA-02 closeout evidence.

Acceptance:
1. Product tasks moved to In Progress with explicit acceptance criteria.
2. Open questions updated with resolved decisions.

## Scheduling Windows

1. Lead Eng (ENG-04/ENG-06): start now.
2. QA closeout rerun: start immediately after ENG-04 closeout evidence lands.
3. Product (PROD-01/PROD-02): start now in draft mode; finalize after WP-03 Done.
4. Marketing (MKT-01/MKT-02): start now in draft mode; finalize after WP-03 Done.
