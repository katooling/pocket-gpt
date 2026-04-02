# Wave-4 Parallel Execution Plan

Status: completed on 2026-03-27.

## Objective

Land the next correctness-and-observability improvements without broadening the blast radius:

- propagate actual executed backend identity into runtime stats and tuning persistence
- make session-cache sidecar metadata self-describing and tamper-detectable

## Team Structure

### Senior Lead

Owner: main agent

Responsibilities:

- keep the backend-identity seam narrow across shared/runtime/Android layers
- integrate package-level verification
- close the wave with explicit residual blockers

### Lead D: Session Cache Metadata

Owner scope:

- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/SessionCacheManager.kt`
- `packages/app-runtime/src/commonTest/kotlin/com/pocketagent/runtime/SessionCacheManagerTest.kt`

Task:

- make `.session.meta` more self-describing and rejectable by future tooling without weakening fail-closed restore behavior

Definition of Done:

1. Saved metadata includes explicit diagnostics fields beyond the identity tuple.
2. Restore rejects tampered or mismatched metadata.
3. Tests cover persisted metadata shape and a rejection path.

### Senior-Lead Local Slice: Backend Identity Propagation

Owner scope:

- `packages/native-bridge/**`
- `packages/core-domain/**`
- `packages/app-runtime/**`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt`

Task:

- propagate actual backend identity from existing backend diagnostics into `RuntimeExecutionStats` and use it for backend-aware tuning writes

Definition of Done:

1. Actual backend identity is derived from existing diagnostics, not requested config.
2. Runtime stats carry that backend identity.
3. Tuning writes use backend identity when present.
4. Recommendation reads remain conservative: exact match first, then legacy, then a unique backend-specific match only when unambiguous.
5. Tests cover the shared seam in package modules.
