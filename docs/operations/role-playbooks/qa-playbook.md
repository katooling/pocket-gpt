# QA Playbook

Last updated: 2026-03-05

## Mission

Provide reproducible release evidence across runtime stability, UX quality, and promotion readiness.

## Current Operating Truth

1. Core technical packages through WP-12 are closed.
2. WP-13 is open due missing moderated cohort metrics.
3. Lane robustness patch (`ENG-19`) is in progress; rerun evidence must be refreshed.

## Done

- [x] `QA-01` through `QA-06` stage evidence gates
- [x] `QA-08` WP-11 UI acceptance suite (`UI-01`..`UI-10`)
- [x] `QA-10` weekly UI regression run-01
- [x] `QA-WP12` closeout reruns and closure recommendation

## In Progress

- [ ] WP-09 weekly rollout quality cadence
- [ ] WP-13 moderated gate prep (script/facilitation scheduling)
- [ ] DX-01 and DX-02 validation support

## Ready Queue

- [ ] `QA-11` lane rerun after `ENG-19` (`android-instrumented`, `maestro`, `journey`)
- [ ] `QA-13` send-capture gate operationalization in weekly lane evidence
- [ ] `QA-WP13-RUN02` moderated 5-user packet execution
- [ ] `QA-12` weekly required-tier + best-effort matrix publication

## Task Queue

| Task ID | Task | Status | Owner | References |
|---|---|---|---|---|
| QA-11 | Full lane rerun after ENG-19 patch | Ready | QA Engineer | `docs/testing/android-dx-and-test-playbook.md`, `docs/operations/evidence/wp-13/2026-03-05-qa-wireless-lane-rerun.md` |
| QA-13 | Send-capture gate operationalization with pass/fail rubric in weekly journey evidence | Ready | QA Engineer | `docs/testing/android-dx-and-test-playbook.md`, `docs/operations/qa-13-send-capture-gate-operationalization.md`, `docs/operations/wp-13-usability-gate-packet-template.md` |
| QA-WP13-RUN02 | Moderated 5-user workflow packet with actual metrics | Ready | QA Lead + Product | `docs/operations/wp-13-usability-gate-packet-template.md`, `docs/operations/evidence/wp-13/2026-03-04-wp13-usability-gate-run-01.md` |
| QA-12 | Weekly regression matrix (required-tier + best-effort + caveats) | Ready | QA Lead | `docs/testing/wp-09-ui-regression-matrix.md` |
| QA-10 | Weekly UI regression matrix run execution | In Progress | QA Lead | `docs/operations/evidence/wp-09/2026-03-04-qa-10-weekly-ui-regression-matrix-run-01.md` |
| QA-DX-01 | Validate Stage-2 quick/closure profile behavior | In Progress | QA Engineer | `docs/testing/test-lane-profiles-and-selection.md` |
| QA-DX-02 | Validate cache telemetry and warm-vs-cold deltas | In Progress | QA Engineer | `docs/testing/stage-2-benchmark-runbook.md` |

## QA Decision Rule

1. Required promotion signals: latest pass ids for `android-instrumented`, `maestro`, `journey` + completed moderated packet.
2. Any open `UX-S0`/`UX-S1` blocker triggers hold recommendation.
3. Weekly matrix must explicitly label required-tier vs best-effort coverage.
4. Journey send-capture stage must show `phase=completed` and `placeholder_visible=false` within SLA for pass.

## QA References

- `docs/operations/execution-board.md`
- `docs/testing/test-strategy.md`
- `docs/testing/android-dx-and-test-playbook.md`
- `docs/operations/wp-13-usability-gate-packet-template.md`
- `docs/operations/qa-13-send-capture-gate-operationalization.md`
- `docs/operations/prod-10-launch-gate-matrix.md`
