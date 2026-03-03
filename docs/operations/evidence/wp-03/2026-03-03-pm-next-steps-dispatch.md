# PM + Product Engineering Dispatch (Post-Reconciliation)

Date: 2026-03-03
Owner: PM/ProdEng
Purpose: Define what is actually next based on repo reality, without duplicating already-completed C4-C7 work.

## Reconciliation Summary

Already complete in commit history (do not re-dispatch):

1. C4 docs single-source consolidation: `2eca77d`
2. C5 Android app module realignment + host lane: `f8f4d49`
3. C6 Stage-2 benchmark wrapper + deterministic outputs + summary JSON: `f8f4d49`
4. C7 nightly hardware lane + stage-close CI enforcement: `d4c3ead`

Recently completed:

1. QA-02 Phase B real Scenario A/B execution with threshold/logcat evidence:
   - `docs/operations/evidence/wp-03/2026-03-03-qa-02-phase-b.md`

## What To Work On Next (Priority Order)

## P0 - Close WP-03 cleanly (required before WP-04/WP-05 package progression)

### Owner: Runtime Eng (ENG-04 closeout)

Objective:
1. Remove remaining Stage-2 ambiguity around placeholder model artifact data.

Deliverables:
1. Replace placeholder artifact checksum values with validated real values where required by runtime artifact flow.
2. Prove artifact selection/checksum path is active in runtime execution path used for Stage-2 closure.
3. Add/update tests for checksum verification behavior with real-format values and failure paths.

Evidence required:
1. `docs/operations/evidence/wp-03/YYYY-MM-DD-eng-04-closeout.md`
2. Referenced raw outputs under the benchmark run root when device validation is involved.

Acceptance criteria:
1. No placeholder checksum remains in active artifact path used for Stage-2 closure.
2. Tests pass for checksum pass/fail/unknown scenarios.
3. Board + engineering playbook status updated.

### Owner: QA (QA-02 closeout refresh)

Objective:
1. Re-run Stage-2 Scenario A/B after ENG-04 closeout to produce final WP-03 benchmark packet.

Deliverables:
1. Fresh Scenario A/B run folder with:
   - `scenario-a.csv`
   - `scenario-b.csv`
   - `stage-2-threshold-input.csv`
   - `threshold-report.txt`
   - `logcat.txt`
   - `notes.md`
2. Updated QA evidence note referencing the new run path.

Evidence required:
1. `docs/operations/evidence/wp-03/YYYY-MM-DD-qa-02-closeout.md`
2. Raw artifacts under the benchmark run root (`YYYY-MM-DD/<device>/...` layout).

Acceptance criteria:
1. Threshold report is PASS for A/B.
2. No template/mock data used as final evidence.
3. Board + QA playbook status updated.

## P1 - Dispatch package after WP-03 closes

### Owner: PM/ProdEng

Objective:
1. Publish owner-level assignment briefs for WP-04 and WP-05.

Deliverables:
1. WP-04 routing/policy/observability packet with explicit battery/thermal/RAM matrix acceptance tests.
2. WP-05 tool safety package completion packet with package-level (not task-level) closure criteria.
3. Product task activation plan (PROD-01/PROD-02) with dependencies explicitly tied to WP-03 completion.

Acceptance criteria:
1. Each owner packet includes: scope, non-goals, exact commands, test/evidence requirements, reviewer, due window.
2. Execution board Ready/In Progress sections reflect those packets.

## Risks and Guardrails

1. Do not conflict with active flow-simplification exploration (`tools/`, `config/`, `tests/`) until ownership/merge strategy is explicit.
2. Keep canonical command surface unchanged while consolidation proposals are evaluated:
   - `bash scripts/dev/test.sh [full|quick|ci]`
   - `bash scripts/dev/device-test.sh [runs] [label] [-- <command...>]`
   - `bash scripts/dev/bench.sh stage2 --device <id> [--date <YYYY-MM-DD>]`
3. Keep raw artifact vs human evidence split strict:
   - raw benchmark run root: `scripts/benchmarks/runs/2026-03-03/RR8NB087YTF/qa-02-phase-b-20260303-203909/`
   - evidence notes: `docs/operations/evidence/...`

## Commands Run for This Dispatch

1. `./gradlew --no-daemon :packages:inference-adapters:test :packages:tool-runtime:test` -> PASS
2. Reviewed evidence and board/playbook sources to validate current state before dispatch update.
