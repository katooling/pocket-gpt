# Open Questions Log

Last updated: 2026-03-04

Track these questions continuously so engineering, product, and go-to-market stay aligned.

## Development Questions

1. What are hard limits for first-token latency and battery drain by tier?
2. What is the minimum acceptable image quality rubric for MVP?

## Product Questions

1. What should be free vs paid in early pricing (if monetization starts at beta)?
2. What UX language best explains local-first privacy without overclaiming?
3. Which features are explicitly excluded from MVP despite high demand?

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
   - Resolved in WP-05 parallel scope (ENG-06) with schema-driven validation and adversarial tests.
   - Decision: ad-hoc parsing is replaced by strict schema validation contracts.
5. For WP-03 closure, what is the exact definition of artifact-validated runtime path?
   - Resolved by WP-03 evidence and closeout records.
   - Decision: active startup path must enforce artifact-manifest validation + SHA checks on runtime-selected artifacts, with QA rerun on artifact-validated path.
6. Are launch workflow and device support decisions still draft, or locked after WP-03?
   - Resolved by lock pass on 2026-03-04 with WP-03 marked Done.
   - Decision: PROD-01 workflow set and PROD-02 device policy are finalized for MVP launch planning.
7. Is PROD-03 fully closed after WP-03 completion?
   - Resolved for scope alignment, not release closure.
   - Decision: checklist alignment is finalized for current scope; final beta acceptance closeout remains gated by Stage 5/6 evidence.
