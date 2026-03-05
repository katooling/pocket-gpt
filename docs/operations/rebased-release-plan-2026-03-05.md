# PO Rebased Release Plan (2026-03-05)

Owner: Product
Support: Engineering, QA, Marketing, Design
Status: Active

## Baseline

1. `WP-00` to `WP-12` are closed.
2. `WP-09` is in progress.
3. `WP-13` remains open pending moderated cohort metrics from run-02.
4. Decision policy is fixed: single-build downloads + `soft gate`.

## Phases

### Phase 1 (March 6-7): Rebase + Stability

1. `DOC-01` docs drift reconciliation across roadmap/ops/playbooks.
2. `ENG-19` preflight robustness for busy media paths.
3. `QA-11` full lane rerun (`android-instrumented`, `maestro`, `journey`) on same device.

### Phase 2 (March 8-12): Pilot + UX Evidence

1. Operate pilot under `PROD-09` policy.
2. Close `UX-13` stuck send + timeout recovery UX contract.
3. Close `ENG-20` runtime cancel/timeout contract hardening.
4. Execute `QA-13` send-capture gate operationalization.
5. Execute `QA-WP13-RUN02` moderated 5-user packet.
6. Execute `MKT-08` proof asset capture + listing shotlist QA.
7. Close `DOC-02` parity sync across PRD + UX docs.

### Phase 3 (March 13-15): Promotion Decision

1. Execute `MKT-09` first 7-day channel scorecard.
2. Execute `QA-12` weekly matrix update.
3. Close `SEC-02` privacy claim parity audit.
4. Publish `MKT-10` claim freeze v1.
5. Publish `PROD-11` pilot support + incident UX-ops playbook.
6. Run `PROD-10` launch gate matrix decision memo.

## Required Pre-Promotion Signals

1. Unit tests and required instrumentation lanes pass.
2. Latest lane pass ids available for `android-instrumented`, `maestro`, `journey`.
3. WP-13 moderated packet fields populated with measured values.
4. No open `UX-S0`/`UX-S1` blockers.

## Interfaces

1. Pilot policy interface: `docs/operations/prod-09-soft-gate-pilot-policy.md`
2. Launch decision interface: `docs/operations/prod-10-launch-gate-matrix.md`
3. Usability packet interface: `docs/operations/wp-13-usability-gate-packet-template.md`

## Contract Changes (Release-Critical)

1. `PROD-10` launch gate matrix is the required cross-functional promotion contract.
2. WP-13 packet schema now requires `run_owner`, `run_host`, and explicit lane pass ids.
3. Recovery/onboarding evidence contract includes event milestones from `UX-12` (including `first_useful_answer_ms`).
4. Send-capture evidence contract requires `phase`, `elapsed_ms`, `runtime_status`, `backend`, `active_model_id`, and `placeholder_visible`.
5. Runtime timeout/cancel contract must define deterministic UI behavior for JNI and fallback runtime paths.

## Required Test Cases Before Next Promotion Step

1. `:apps:mobile-android:testDebugUnitTest` PASS.
2. `connectedDebugAndroidTest` PASS on required-tier device.
3. `devctl lane maestro` PASS after preflight robustness patch.
4. `devctl lane journey --repeats 1 --reply-timeout-seconds 90` PASS with send-capture `phase=completed` and `placeholder_visible=false`.
5. `QA-WP13-RUN02` moderated workflow packet complete and reviewed.
6. Weekly QA regression matrix updated with severity deltas.
7. First channel scorecard run completed with claim-safety audit.
8. Claim freeze v1 published with privacy parity audit references.
