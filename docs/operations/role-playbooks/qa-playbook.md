# QA Playbook

Last updated: 2026-03-04

## Mission

Ensure performance, reliability, safety, and privacy quality gates are met with reproducible evidence before release decisions.

## Where To Start

1. Check `docs/operations/execution-board.md`.
2. Run stage-appropriate tests from the Android playbook and test strategy.
3. Store evidence artifacts in deterministic benchmark run paths.

## What Is Done

- [x] Benchmark protocol and threshold scripts prepared
- [x] Stage-by-stage QA gate structure documented
- [x] Go/no-go packet template prepared
- [x] QA-01 Stage 1 smoke loop on physical Android completed (`docs/operations/evidence/wp-02/2026-03-04-qa-01.md`)
- [x] QA-02 Phase B real Scenario A/B run executed with threshold/logcat evidence (`docs/operations/evidence/wp-03/2026-03-03-qa-02-phase-b.md`)
- [x] QA-02 final closeout rerun executed on artifact-validated runtime path with fresh Scenario A/B threshold PASS packet (`docs/operations/evidence/wp-03/2026-03-04-qa-02-closeout.md`)
- [x] QA-03 routing/policy boundary regression rerun executed and passed on incoming WP-04 state (`docs/operations/evidence/wp-04/2026-03-04-qa-03-rerun.md`)
- [x] QA-04 tool safety adversarial regression rerun executed and passed on final WP-05 state (`docs/operations/evidence/wp-05/2026-03-04-qa-04-rerun.md`)
- [x] QA-05 prep execution completed (baseline lanes + available memory/image-adjacent checks) with blocker map published (`docs/operations/evidence/wp-06/2026-03-04-qa-05-prep.md`)

## What Is In Progress

- [ ] None

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| QA-01 | Validate Stage 1 smoke loop on physical Android | Done | WP-02 | QA Engineer | `docs/testing/android-dx-and-test-playbook.md`, `docs/operations/evidence/wp-02/2026-03-04-qa-01.md` |
| QA-02 | Run Scenario A/B with real Qwen model and evaluate thresholds | Done (Final closeout) | WP-03 | QA Engineer | `docs/feasibility/benchmark-protocol.md`, `docs/testing/stage-2-benchmark-runbook.md`, `docs/operations/evidence/wp-03/2026-03-03-qa-02-phase-b.md`, `docs/operations/evidence/wp-03/2026-03-04-qa-02-closeout.md` |
| QA-03 | Routing/policy boundary regression suite | Done (Rerun PASS logged 2026-03-04) | WP-04 | QA + Security | `docs/testing/test-strategy.md`, `docs/operations/evidence/wp-04/2026-03-04-qa-03.md`, `docs/operations/evidence/wp-04/2026-03-04-qa-03-rerun.md` |
| QA-04 | Tool safety adversarial regression suite | Done (Rerun PASS logged 2026-03-04) | WP-05 | QA + Security | `docs/testing/test-strategy.md`, `docs/operations/evidence/wp-05/2026-03-04-qa-04.md`, `docs/operations/evidence/wp-05/2026-03-04-qa-04-rerun.md` |
| QA-05 | Scenario C image and memory acceptance runs | Blocked (Prep complete; waiting ENG-07 WP-06 deliverables) | WP-06 | QA Engineer | `docs/roadmap/mvp-implementation-tracker.md`, `docs/operations/evidence/wp-06/2026-03-04-qa-05-prep.md` |
| QA-06 | 30-minute soak and crash/OOM/ANR evidence pack | Backlog | WP-07 | QA Lead | `docs/roadmap/mvp-beta-go-no-go-packet.md` |
| QA-07 | Voice STT/TTS quality benchmark framework (post-MVP) | Backlog | WP-10 | QA Lead | `docs/roadmap/product-roadmap.md` |

## QA Definition of Done

1. Required test suite executed for the stage.
2. Evidence artifacts archived in expected structure.
3. Failures triaged with severity and owner.
4. Gate decision recorded and synced to execution board.

## QA References

- `docs/operations/execution-board.md`
- `docs/testing/test-strategy.md`
- `docs/testing/android-dx-and-test-playbook.md`
- `docs/feasibility/benchmark-protocol.md`
- `docs/roadmap/mvp-beta-go-no-go-packet.md`
