# QA Playbook

Last updated: 2026-03-05

## Mission

Ensure performance, reliability, safety, privacy, and user-facing UX quality gates are met with reproducible evidence before release decisions.

## Where To Start

1. Check `docs/operations/execution-board.md`.
2. Run stage-appropriate tests from the Android playbook and test strategy.
3. Store evidence artifacts in deterministic benchmark run paths.

## What Is Done

- [x] QA-01 Stage 1 smoke loop on physical Android completed
- [x] QA-02 Scenario A/B final closeout rerun completed
- [x] QA-03 routing/policy regression rerun completed
- [x] QA-04 tool safety adversarial rerun completed
- [x] QA-05 Scenario C image + memory acceptance completed
- [x] QA-06 30-minute soak and crash/OOM/ANR evidence pack completed
- [x] QA-08 WP-11 UI acceptance suite gate completion (`UI-01`..`UI-10` evidence complete)
- [x] QA-10 weekly UI regression matrix run 01 completed for WP-09 rollout operations
- [x] QA-WP12 rerun completed with WP-12 close recommendation

## What Is In Progress

- [ ] WP-09 rollout quality execution support (weekly cadence)
- [ ] DX-01 validation support for Stage-2 quick/closure lane split and resume workflow
- [ ] DX-02 validation support for prefix-cache telemetry and warm-vs-cold runtime checks

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| QA-01 | Validate Stage 1 smoke loop on physical Android | Done | WP-02 | QA Engineer | `docs/operations/evidence/wp-02/2026-03-04-qa-01.md` |
| QA-02 | Run Scenario A/B with real Qwen model and evaluate thresholds | Done | WP-03 | QA Engineer | `docs/operations/evidence/wp-03/2026-03-04-qa-02-closeout.md` |
| QA-03 | Routing/policy boundary regression suite | Done | WP-04 | QA + Security | `docs/operations/evidence/wp-04/2026-03-04-qa-03-rerun.md` |
| QA-04 | Tool safety adversarial regression suite | Done | WP-05 | QA + Security | `docs/operations/evidence/wp-05/2026-03-04-qa-04-rerun.md` |
| QA-05 | Scenario C image and memory acceptance runs | Done | WP-06 | QA Engineer | `docs/operations/evidence/wp-06/2026-03-04-qa-05.md` |
| QA-06 | 30-minute soak and crash/OOM/ANR evidence pack | Done | WP-07 | QA Lead | `docs/operations/evidence/wp-07/2026-03-04-qa-06.md` |
| QA-08 | WP-11 UI acceptance suite (`UI-01`..`UI-10`) | Done (rerun complete; gate closed 2026-03-04) | WP-11 first integrated slice | QA Engineer | `docs/prd/phase-0-prd.md`, `docs/roadmap/mvp-implementation-tracker.md`, `docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md` |
| QA-09 | WP-09 rollout quality checkpoints + beta operations QA support | Done (template packet delivered 2026-03-04) | WP-07, WP-11 | QA Lead | `docs/operations/evidence/wp-09/2026-03-04-prod-06-kickoff.md`, `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-rollout-quality-checkpoints.md`, `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-incident-triage-template.md`, `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-release-promotion-checklist.md`, `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-weekly-rollout-summary-template.md` |
| QA-10 | Weekly UI regression matrix execution for beta operations | Done (run 01 complete; weekly cadence active) | WP-11, QA-08 | QA Lead | `docs/operations/evidence/wp-09/2026-03-04-qa-10-weekly-ui-regression-matrix-run-01.md`, `docs/testing/wp-09-ui-regression-matrix.md` |
| QA-WP12 | Validate backend production runtime closure packet (ENG-12..ENG-17) | Done (rerun executed 2026-03-04; recommendation: close WP-12) | ENG-12, ENG-13, ENG-14, ENG-15, ENG-16, ENG-17 evidence notes | QA Engineer | `docs/operations/evidence/wp-12/2026-03-04-qa-wp12-closeout.md`, `docs/testing/android-dx-and-test-playbook.md`, `docs/testing/test-strategy.md` |
| QA-DX-01 | Validate Stage-2 quick/closure profile behavior + resume artifact integrity | In Progress | DX-01 implementation | QA Engineer | `docs/testing/stage-2-benchmark-runbook.md`, `docs/testing/test-lane-profiles-and-selection.md` |
| QA-DX-02 | Validate prefix-cache counters, warm-vs-cold deltas, and closure artifact compatibility | In Progress | DX-02 implementation | QA Engineer | `scripts/benchmarks/runs/YYYY-MM-DD/<device>/summary.json`, `docs/testing/stage-2-benchmark-runbook.md` |
| QA-07 | Voice STT/TTS quality benchmark framework (post-MVP) | Backlog | WP-10 | QA Lead | `docs/roadmap/product-roadmap.md` |

## QA Definition of Done

1. Required test suite executed for the stage/package.
2. Evidence artifacts archived in expected structure.
3. Failures triaged with severity and owner.
4. Gate decision recorded and synced to execution board.

## QA References

- `docs/operations/execution-board.md`
- `docs/testing/test-strategy.md`
- `docs/testing/android-dx-and-test-playbook.md`
- `docs/roadmap/mvp-beta-go-no-go-packet.md`
