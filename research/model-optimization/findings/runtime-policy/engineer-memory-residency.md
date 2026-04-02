# Runtime Policy Engineer Notes: Memory Estimation, Residency, And Load Planning

## Scope

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/ModelMemoryEstimator.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeModelMemoryEstimator.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/MemoryBudgetTracker.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt`

## Findings

### 1. Load planning already has a real memory-estimate gate

Evidence:

- `RuntimePlanResolver` computes a `RuntimeMemoryEstimate`, tries to fit the requested config, reduces context through a fixed candidate ladder, then optionally clamps GPU layers, and finally blocks the load if the tracked safe ceiling is still exceeded: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:180-297`

Why it matters:

- Pocket GPT is not blindly loading models. There is a real pre-flight planner.
- This is one of the stronger foundations already present in the codebase.

### 2. Memory adaptation is still narrow and leaves obvious tuning levers untouched

Evidence:

- The memory-repair path only tries:
  - lower `nCtx`
  - lower GPU layers when a recommendation is available
  - zero out speculative draft GPU layers after a failed GPU-layer retry
  `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:221-279`
- It does not try smaller `nBatch`, smaller `nUbatch`, alternate KV cache types, or turning speculative decoding off before blocking the load.

Why it matters:

- Pocket GPT can reject a load that might have been made safe by cheaper parameter reductions than a hard block.

### 3. Android-side estimation and runtime-side estimation are close, but not fully aligned

Evidence:

- `ModelMemoryEstimator` on Android documents a PocketPal-style formula and, when metadata is absent, falls back with fixed `nCtx=2048` and `nUbatch=512`: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/ModelMemoryEstimator.kt:10-19`, `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/ModelMemoryEstimator.kt:117-145`
- The runtime estimator is more precise and accepts actual `nCtx`, KV types for K/V, and `nUbatch`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeModelMemoryEstimator.kt:16-73`

Why it matters:

- The app can present a memory estimate to the user that diverges from the actual load planner when metadata is missing or when requested config differs from the fallback assumptions.

### 4. The memory tracker captures useful signals but the planner does not yet use all of them

Evidence:

- `MemoryBudgetTracker` stores `availableMemoryCeilingMb`, `largestSuccessfulLoadMb`, and a short successful-load history: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/MemoryBudgetTracker.kt:14-107`
- `RuntimePlanResolver` only reads `availableMemoryCeilingMb`; it does not use `largestSuccessfulLoadMb` or per-model successful history during planning: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:194-213`

Why it matters:

- The system is collecting more signal than it currently exploits.
- That is a missed opportunity for model-specific memory safety guidance.

### 5. Residency policy is present, but simplified

Evidence:

- `resolveKeepAliveMs(...)` uses pressure ratio, thermal level, and battery level to choose a TTL: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:379-408`
- `ChatStreamRequestPlanner` exposes user-facing keep-alive modes and maps them to `ModelResidencyPolicy`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ChatRuntimeService.kt:175-214`

Why it matters:

- The residency model is not missing, but it is based on static heuristics rather than real model reload cost, warmup benefit, or app-usage patterns.

### 6. `mmap` and `mlock` policy is extremely coarse

Evidence:

- `applyMmapStrategy(...)` keeps mmap enabled only when `ramClassGb > 4` or the model exceeds 2 GB: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:337-344`
- `shouldUseMlock(...)` only checks `ramClassGb >= 12` and model size in the range `1 MB..2048 MB`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:346-349`

Why it matters:

- These are blunt heuristics with no backend, storage, or device-I/O awareness.
- They are likely placeholders, not final optimized policy.

## Open Questions

1. Should memory repair try smaller `nBatch` and `nUbatch` before declaring a load unsafe?
2. Should the planner automatically disable speculative decoding during memory rescue attempts?
3. Can successful per-model RSS history be turned into a better first-pass compatibility decision than the current generic ceiling?
