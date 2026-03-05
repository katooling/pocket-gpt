# Phase 1 MVP Plan

Last updated: 2026-03-05

## Objective

Ship a usable, privacy-first Android MVP under a single-build download policy, with technical lanes stable and moderated usability evidence completed before broader promotion.

## Current Truth (2026-03-05)

1. `WP-00` to `WP-12` are closed.
2. `WP-09` is in progress.
3. `WP-13` remains open (`run-01` decision = `hold`) until moderated cohort metrics are attached.
4. Technical signal is strong (`:apps:mobile-android:testDebugUnitTest`, `connectedDebugAndroidTest`, and devctl lane evidence are available), while operational UX signal is incomplete.
5. Active launch policy: single-build downloads + `soft gate` for pilot expansion.

Execution tracker:

- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/operations/execution-board.md`
- `docs/operations/prod-09-soft-gate-pilot-policy.md`
- `docs/operations/prod-10-launch-gate-matrix.md`

## Rebased Release Phases (Decision Complete)

### Phase 1: Rebase + Stability (March 6-7, 2026)

1. `DOC-01` reconcile status/date drift across roadmap and operations docs.
2. `ENG-19` harden devctl preflight behavior for busy media paths with deterministic retry/fallback.
3. `QA-11` rerun `android-instrumented`, `maestro`, and `journey` on the same target device and publish pass ids.

Exit criteria:

1. No contradictory statuses/dates in core roadmap/ops docs.
2. Maestro lane not blocked by false storage preflight failures.

### Phase 2: Pilot + UX Evidence (March 8-12, 2026)

1. `PROD-09` soft-gate pilot policy published with explicit cohort size, duration, and escalation thresholds.
2. `UX-12` recovery journey spec published (`NotReady -> setup -> Ready`) with measurable acceptance.
3. `UX-13` closes stuck-send/timeout recovery UX contract and assertions.
4. `ENG-20` finalizes runtime cancel/timeout semantics across JNI + fallback paths.
5. `QA-13` operationalizes send-capture gate in weekly QA cadence.
6. `QA-WP13-RUN02` executes moderated 5-user packet and fills all threshold fields.
7. `MKT-08` captures proof assets and finalizes listing shotlist.
8. `DOC-02` synchronizes PRD + UX docs to runtime timeout/cancel/send-capture behavior.

Exit criteria:

1. Pilot running under documented policy.
2. Moderated packet has measured values (no `not collected` placeholders).
3. Timeout/cancel + send-capture behavior has explicit product documentation and QA rubric.
4. Claim-safe proof assets are approved for decision review.

### Phase 3: Promotion Decision + Ops Loop (March 13-15, 2026)

1. `MKT-09` executes first 7-day channel scorecard.
2. `QA-12` publishes required-tier + best-effort weekly matrix.
3. `SEC-02` privacy claim parity audit finalization.
4. `MKT-10` claim freeze v1 publication.
5. `PROD-11` pilot support and incident UX-ops playbook publication.
6. `PROD-10` runs promote/hold decision through a single launch gate matrix.

Exit criteria:

1. Decision memo includes rationale, risk, and next-step scope.
2. `promote` path defines next cohort cap and support SLA.
3. `hold` path defines top-3 blockers with owners/dates.

## Promotion Rule

1. Soft gate allows controlled pilot expansion only.
2. Broad promotion remains blocked until moderated WP-13 packet is complete and reviewed.
3. Launch claims must map to evidence IDs and lane pass IDs.

## MVP Completion Definition

MVP release decision is valid only when all are true:

1. Core workflow, stability, and policy lanes pass on required-tier device.
2. `WP-13` moderated usability packet is complete with threshold outcomes.
3. Launch gate matrix links story -> flow -> tests -> evidence -> claim for every publishable claim.
4. Product, QA, Engineering, and Marketing record a dated promote/hold decision.
