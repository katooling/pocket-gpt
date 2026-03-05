# Open Questions Log

Last updated: 2026-03-04

Track these questions continuously so engineering, product, and go-to-market stay aligned.

## Development Questions

1. What are hard limits for first-token latency and battery drain by tier?
2. What is the minimum acceptable image quality rubric for MVP?
3. What are pass/fail thresholds for UI responsiveness under sustained on-device load?

## Product Questions

1. What should be free vs paid in early pricing (if monetization starts at beta)?
2. What UX language best explains local-first privacy without overclaiming?
3. Which features are explicitly excluded from MVP despite high demand?
4. Which device classes are mandatory for WP-11 UI acceptance evidence beyond the current primary test phone?

## Marketing Questions

1. Is positioning centered on privacy, offline reliability, or both equally?
2. Which competitor comparisons are safe and evidence-backed?
3. Which user proof points (latency, offline behavior, no-cloud path) should be shown publicly?

## Business and Distribution Questions

1. Android-first only at first launch, or dual-platform beta messaging from day one?
2. Which distribution channels are highest leverage first (Play Store, communities, partnerships)?
3. What support model is needed for device-compatibility edge cases?
4. What telemetry, if any, is acceptable under privacy promises and user consent?

## Governance

1. Review weekly.
2. Convert resolved questions into ADRs, roadmap updates, or release checklist items.

## Resolved (2026-03-04)

1. Which Android device classes are required for launch support, and which are best-effort?
   - Resolved by `docs/feasibility/device-matrix.md` (MVP Launch Device Policy decision table).
   - Decision: Android Mid + High = Required; Android Low = Best-effort; iOS = Best-effort/post-MVP for launch SLA.
2. Which 2-3 workflows must be excellent at launch for core users?
   - Resolved by `docs/prd/phase-0-prd.md` (Launch Workflow Lock section).
   - Decision: Offline Quick Answer, Offline Task Assist, Context Follow-up.
3. Should stage runner and benchmark tooling be fully CI-automated before real runtime integration?
   - Resolved in execution flow by completion of WP-01/WP-02 baseline and current stage governance in tracker docs.
   - Decision: CI-first baseline is required; physical-device evidence remains mandatory for launch gates.
4. What strict schema framework should replace current lightweight tool parsing?
   - Resolved in WP-05 scope (ENG-06) with schema-driven validation and adversarial tests.
   - Decision: ad-hoc parsing is replaced by strict schema validation contracts.
5. For WP-03 closure, what is the exact definition of artifact-validated runtime path?
   - Resolved by WP-03 evidence and closeout records.
   - Decision: active startup path enforces artifact-manifest validation + SHA checks with QA rerun on validated path.
6. Are launch workflow and device support decisions still draft, or locked after WP-03?
   - Resolved by lock pass on 2026-03-04 with WP-03 marked Done.
   - Decision: PROD-01 workflow set and PROD-02 device policy are finalized for MVP launch planning.
7. Is PROD-03 fully closed after WP-03 completion?
   - Resolved for scope alignment, not release closure.
   - Decision: checklist alignment finalized for current scope; final beta acceptance remains gated by WP-07 and WP-11 closure.
8. Is user-facing MVP UI optional for external beta?
   - Resolved in product recovery policy.
   - Decision: external beta/go-live signoff requires both WP-07 (hardening) and WP-11 (user-facing UI) to be Done.
9. Which UI architecture path should be used for MVP?
   - Resolved in product recovery implementation.
   - Decision: Jetpack Compose + ViewModel with an app-facing runtime facade (`MvpRuntimeFacade`) and advanced controls in sheet UX.
10. Is external beta still blocked after WP-07 packet signoff and WP-11 closure?
   - Resolved by final Product/QA/Engineering gate approvals on 2026-03-04.
   - Decision: no remaining Product/QA gate blocker; proceed with WP-09 distribution + beta operations execution.
11. When can marketing show a real screenshot of the app doing useful work?
   - Resolved by gate evidence review.
   - Decision: start screenshot/video capture now for launch assets; publish externally only after asset QA and copy QA pass.
   - References:
     - `docs/operations/evidence/wp-07/2026-03-04-prod-03-final-signoff.md`
     - `docs/operations/evidence/wp-11/2026-03-04-prod-qa-eng-wp11-closeout.md`
12. Should marketing wait for real inference or start with architecture/privacy story?
   - Resolved by messaging sequencing rule.
   - Decision: run both in sequence now: architecture/privacy + reliability proof first, paired with real inference screenshots/video in the same launch cycle.
   - References:
     - `docs/operations/mkt-01-messaging-architecture-draft.md`
     - `docs/operations/mkt-04-landing-page-launch-copy-v1-draft.md`
13. Who should first beta testers be: technical community or general consumers?
   - Resolved by staged rollout strategy.
   - Decision: start with technical communities and closed cohorts, then broaden after first feedback/activation loop stabilizes.
   - References:
     - `docs/operations/mkt-03-launch-channel-test-plan-draft.md`
     - [support.google.com/googleplay/android-developer/answer/9845334](https://support.google.com/googleplay/android-developer/answer/9845334)
14. Is privacy-first positioning strong enough without a working demo?
   - Resolved by conversion-risk policy.
   - Decision: privacy-first alone is insufficient for launch conversion; require real product proof (screenshots/video + evidence links) on primary launch surfaces.
   - References:
     - `docs/operations/mkt-01-messaging-architecture-draft.md`
     - `docs/operations/mkt-04-landing-page-launch-copy-v1-draft.md`
15. What does the competitor landscape look like now that matrix cells were unknown?
   - Resolved by external source-backed snapshot.
   - Decision: matrix now has sourced baseline for ChatGPT/Gemini/Claude; reliability artifact parity remains explicitly `Unknown` where no public equivalent evidence packet exists.
   - References:
     - `docs/operations/mkt-02-competitor-matrix-template-draft.md`
     - `docs/operations/evidence/wp-08/2026-03-04-mkt-02-external-competitor-research.md`
16. Which launch distribution path should be used first: Play Store, direct APK, or community beta?
   - Resolved by channel-governance decision.
   - Decision: primary path is Google Play internal/closed testing + community recruitment funnel; direct APK is contingency-only for tightly controlled technical testers.
   - References:
     - [support.google.com/googleplay/android-developer/answer/9845334](https://support.google.com/googleplay/android-developer/answer/9845334)
     - [support.google.com/googleplay/android-developer/answer/16360545](https://support.google.com/googleplay/android-developer/answer/16360545)
17. For ENG-12, what model artifact distribution path and provenance policy should engineering implement now?
   - Resolved by Product decision for WP-12 backend runtime closure.
   - Decision: side-load (manual/internal only) as the single default path; model load is hard-blocked unless manifest + SHA-256 + provenance signature checks pass.
   - References:
     - `docs/operations/evidence/wp-12/2026-03-04-prod-eng-12-model-distribution-decision.md`
     - `docs/operations/execution-board.md`
