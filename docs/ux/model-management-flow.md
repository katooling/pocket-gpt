# Model Management and Runtime Readiness Flow

Last updated: 2026-03-04
Owner: Runtime + Android
Status: MVP partial (status surface implemented, full download manager pending)

## Current MVP Behavior

Runtime status is shown in-app as one of:

1. `Not ready` - required model artifacts are missing or unverified.
2. `Loading` - runtime/model load in progress.
3. `Ready` - runtime available for inference/tool paths.
4. `Error` - startup or runtime failure state.

## Side-load Provisioning Path (Current)

1. Push GGUF files to device storage.
2. Export:
   - `POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH`
   - `POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH`
3. Run Stage-2 benchmark and runtime evidence validator.

Helper script:

- `scripts/android/provision_sideload_models.sh`

## Gaps (Post-MVP)

1. In-app model download manager (queue/progress/pause).
2. Storage usage and eviction controls.
3. Versioned model switching UX.
4. Recovery UX when model checksums/provenance fail.

## Acceptance for Future Model Manager Ticket

1. User can see installed models and sizes.
2. User can start/monitor model download progress.
3. User can recover from failed download/verification.
4. Runtime status transitions are reflected in UI within 1 second.
