# Runtime Policy Engineer Notes: Tuning Recommendations And Feedback Loops

## Scope

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt`
- `apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/RuntimeTuningDeciderTest.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppDependencies.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeGateway.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModelSendWorkflow.kt`

## Findings

### 1. The tuning loop exists, but it only controls a narrow subset of the runtime surface

Evidence:

- `RuntimeTuningRecommendation` can adjust only `gpuLayers`, `kvCacheType`, `speculativeEnabled`, `speculativeDraftGpuLayers`, `useMmap`, `nBatch`, and `nUbatch`: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:44-67`
- It cannot tune `nCtx`, `nThreads`, `nThreadsBatch`, `flashAttnMode`, `nKeep`, routing choice, or sampler defaults, even though those are part of `PerformanceRuntimeConfig`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt:14-53`

Why it matters:

- The feedback loop is real, but it does not cover several high-leverage parameters.
- This means Pocket GPT can only partially adapt even when it has runtime evidence.

### 2. Recommendation identity is too coarse for reliable reuse

Evidence:

- Recommendations are keyed by `deviceKey + profile + mode + modelId`: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:559-563`, `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:493-500`
- The key does not include context length, model version, quantization variant, backend profile, or flash-attention state.

Why it matters:

- A recommendation learned for one context length or model artifact can be replayed onto a materially different configuration.
- This is especially risky for GPU layer counts, batch sizes, and mmap behavior.

Recommendation:

- Expand the tuning key to include at least model version, quantization class, context bucket, and backend identity.

### 3. Failure classification is substring-driven and easy to misclassify

Evidence:

- The decider demotes behavior based on simple string checks such as `"gpu"`, `"opencl"`, `"hexagon"`, `"backend"`, `"jni"`, `"draft"`, `"speculative"`, `"mmap"`, `"readahead"`, `"memory"`, `"alloc"`, and `"oom"`: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:124-187`

Why it matters:

- This is a pragmatic first pass, but it is not robust enough for a long-lived optimization system.
- It can both miss real regressions and overreact to loosely related error strings.

Recommendation:

- Promote backend-specific error enums and structured failure reasons into the tuning layer instead of parsing free-form substrings.

### 4. Timeouts and cancellations do not feed the tuning loop

Evidence:

- `recordFailure(...)` returns early for blank errors, `"cancelled"`, and `"timeout"`: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:414-417`

Why it matters:

- A timeout is often an optimization signal, especially for over-aggressive context, speculative decode, or GPU offload settings.
- Excluding timeouts means the system is blind to a class of performance failures that matter in practice.

### 5. Promotion logic is quality-gated, but still global and heuristic-heavy

Evidence:

- Promotion requires three quality wins and uses fixed thresholds for peak RSS, first-token latency, and tokens/sec: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:152-156`, `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:189-239`, `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:308-316`
- Tests confirm promotion and demotion are threshold-driven rather than statistically validated: `apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/RuntimeTuningDeciderTest.kt:10-137`

Why it matters:

- The current design is understandable and deterministic, but it is not yet a rigorous tuning framework.
- It lacks variance handling, backend-specific thresholds, and per-model calibration.

### 6. The tuning loop is wired into the request path and response path correctly

Evidence:

- The Android runtime facade injects recommendations via `recommendedGpuLayers` when building the production runtime: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppDependencies.kt:60-80`
- `ChatStreamRequestPlanner` and `RuntimeGateway` apply `RuntimeTuning.applyRecommendedConfig(...)` before streaming: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ChatRuntimeService.kt:48-69`, `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeGateway.kt:83-95`
- Success and failure telemetry are recorded at the UI workflow boundary after generation completes or fails: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModelSendWorkflow.kt:188-205`, `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModelSendWorkflow.kt:384-403`

Why it matters:

- The architecture has the right control points.
- The main issue is not missing plumbing; it is the narrow parameter scope and coarse policy logic.

## Open Questions

1. Should timeouts be treated as demotion signals when they recur on the same tuning key?
2. Which parameters should move into the first expansion of the tuning loop: `nCtx`, `flashAttnMode`, `nThreadsBatch`, or routing choice?
3. Should the tuning store separate “safe fallback recommendation” from “promotion candidate under experimentation”?
