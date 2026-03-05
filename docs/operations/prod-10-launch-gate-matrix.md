# PROD-10 Launch Gate Matrix

Last updated: 2026-03-05
Owner: Product
Support: QA, Engineering, Marketing
Status: Ready

## Purpose

Single promotion interface for release decisions. Every publishable claim must map to a validated user story, flow, test, and evidence chain.

## Gate Modes

1. Required: must pass for any promotion decision.
2. Advisory: informs scope/pace of expansion, but does not block pilot continuation alone.

## Matrix

| Story ID | User Story | UX Flow Reference | Test IDs / Lanes | Evidence IDs | Claim ID | Gate Type | Current State |
|---|---|---|---|---|---|---|---|
| S-A | Offline quick answer works in first session | `docs/prd/phase-0-prd.md` Workflow A | `MainActivityUiSmokeTest`, `devctl lane android-instrumented`, Maestro scenario A | `docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md`, `docs/operations/evidence/wp-13/2026-03-05-qa-wireless-lane-rerun.md` | C-01 offline quick-answer reliability | Required | PASS |
| S-B | Local task/tool flow completes without cloud dependency | `docs/prd/phase-0-prd.md` Workflow B | `MainActivityUiSmokeTest`, Maestro scenario B, journey lane | `docs/operations/evidence/wp-09/2026-03-04-qa-10-weekly-ui-regression-matrix-run-01.md`, `docs/operations/evidence/wp-13/2026-03-05-qa-wireless-lane-rerun.md` | C-02 local tool utility | Required | PASS |
| S-C | Context follow-up (incl. optional image) stays coherent | `docs/prd/phase-0-prd.md` Workflow C | Maestro scenario C, journey aggregate | `docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md`, `docs/operations/evidence/wp-13/2026-03-05-qa-wireless-lane-rerun.md` | C-03 context continuity + image support | Required | PASS |
| S-D | User can recover from `NotReady` to `Ready` | `docs/ux/ux-12-recovery-journey-spec.md`, `docs/ux/model-management-flow.md` | `RealRuntimeProvisioningInstrumentationTest`, journey lane, moderated WP-13 script | `docs/operations/evidence/wp-13/2026-03-05-eng-p1-model-manager-phase2-closure.md`, `docs/operations/evidence/wp-13/2026-03-04-wp13-usability-gate-run-01.md` | C-04 first-run setup clarity | Required | HOLD (moderated metrics missing) |
| S-E | Privacy boundaries and controls are understandable | `docs/security/privacy-model.md`, `docs/ux/implemented-behavior-reference.md` | UI smoke privacy checks, moderated WP-13 privacy comprehension metrics | `docs/operations/evidence/wp-13/2026-03-04-wp13-usability-gate-run-01.md` | C-05 privacy-first trust | Required | HOLD (moderated metrics missing) |
| A-01 | Time-to-first-useful-answer meets pilot target | `docs/ux/ux-12-recovery-journey-spec.md` success targets | journey report timing + moderated notes | pending `QA-WP13-RUN02` packet | C-06 speed perception | Advisory | Pending |
| A-02 | Channel engagement signal supports expansion | `docs/operations/mkt-03-7-day-scorecard-template.md` | `MKT-09` scorecard execution | pending `MKT-09` run-01 | C-07 channel fit | Advisory | Pending |

## Pilot Promotion Checklist

Required for `promote`:

1. All required rows = `PASS`.
2. No open `UX-S0`/`UX-S1` blockers.
3. Latest lane pass ids recorded for `android-instrumented`, `maestro`, and `journey`.
4. WP-13 moderated packet contains measured values (no `not collected` fields).

Advisory for scope sizing:

1. `first_useful_answer_ms` trend.
2. Onboarding + recovery completion time distribution.
3. 7-day scorecard keep/iterate/stop recommendation.

## Decision Log

| Decision Date (UTC) | Window | Recommendation | Rationale | Next Scope |
|---|---|---|---|---|
| 2026-03-05 | Pre-run-02 baseline | Hold | WP-13 run-01 has missing moderated cohort metrics | Execute `QA-WP13-RUN02`, then rerun matrix |
