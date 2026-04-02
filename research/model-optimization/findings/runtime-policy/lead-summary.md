# Runtime Policy Lead Summary

Status: Initial evidence-backed pass complete.

## Scope

- `packages/app-runtime/`
- `packages/inference-adapters/`
- Android runtime tuning and memory estimation
- routing policy and performance profiles

## Findings

## Current Policy And Tuning Architecture

- Profile defaults are created in `PerformanceRuntimeConfig.forProfile(...)`, which defines threads, batch sizes, context, sampler settings, speculative defaults, mmap, and `nKeep` from three high-level profiles: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt:68-176`
- Request preparation applies those profile defaults, then optionally applies persisted tuning recommendations before building the runtime request: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ChatRuntimeService.kt:48-69`
- Runtime planning then mutates the request again through context bucketing, thermal overrides, cached GPU-layer estimates, memory-fit planning, speculative gating, mmap strategy, and residency TTL resolution: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:39-159`
- Adaptive model routing is handled separately through `AdaptiveRoutingPolicy`, which uses static model ranks plus battery, thermal, and RAM heuristics: `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/AdaptiveRoutingPolicy.kt:4-60`
- A persistent Android-side tuning loop records success and failure outcomes and replays recommendations per device/profile/mode/model key: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:320-420`

## Concrete Findings

### 1. The architecture has the right control points, but the policy logic is still mostly heuristic

Evidence:

- There are clear stages for preset selection, request planning, runtime mutation, and telemetry-driven recommendation replay.
- However, most decisions are based on fixed tables and threshold heuristics rather than measured device/model behavior.

Impact:

- Pocket GPT has an optimization framework scaffold, but not yet a mature optimization control plane.

### 2. Speculative decoding is the clearest gap between available runtime mechanism and safe policy

Evidence:

- Non-battery profiles default speculative decoding on with a single hard-coded draft model id: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt:142-150`
- The later gate only checks RAM and draft-path presence, not tokenizer/vocabulary compatibility or verified model pairing: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:356-377`

Assessment:

- This is likely too weak for production optimization policy.
- It should move to an explicit compatibility matrix and benchmark-backed enablement policy.

### 3. GPU batch policy is inconsistent enough to obscure real tuning behavior

Evidence:

- Profile defaults set large GPU batches: `512` in `BALANCED`, `768` in `FAST`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt:88-104`
- The request planner later hard-clamps all GPU requests to `256`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ChatRuntimeService.kt:167-173`

Assessment:

- The effective runtime surface is smaller than the apparent one.
- This should be normalized before serious parameter tuning begins.

### 4. The tuning loop is useful but too narrow and too coarsely keyed

Evidence:

- Tunable fields are limited to GPU layers, KV type, speculative flags, mmap, and batch sizes: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:44-67`
- Recommendation keys omit context length, model version, quantization class, and backend identity: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:493-500`, `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:559-563`

Assessment:

- The loop can help recover from obvious regressions.
- It is not yet a reliable source of reusable optimization knowledge.

### 5. Memory-fit planning is one of the strongest existing optimization policies, but it still leaves performance levers unused

Evidence:

- Load planning already uses model metadata and a safe-memory ceiling to reduce context and sometimes clamp GPU layers before loading: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:180-297`
- It does not yet attempt smaller batch sizes, alternate KV cache types, or disabling speculative decoding before load blocking.

Assessment:

- This is a solid foundation for safe optimization work.
- It should become the center of future parameter rescue logic rather than a narrow pre-flight gate.

## Risky Defaults, Hard-Coded Assumptions, And Missing Adaptation

- Speculative decoding is treated as a profile feature instead of a verified model-pair feature.
- Context, batch, and routing policy rely on generic RAM and thermal thresholds rather than model-specific evidence.
- Timeout outcomes are ignored by the tuning store, which removes a useful optimization failure signal: `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt:414-417`
- `mmap` and `mlock` strategy is based on very coarse RAM and model-size checks: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:337-349`
- Context selection and prompt budgeting are not tightly aligned, which may hide real context-related wins or regressions.

## Gaps Between Runtime Mechanisms And Policy Selection

- Native runtime supports richer behavior than policy currently reasons about; the policy layer does not tune flash-attention mode, thread counts, `nKeep`, or per-model speculative pairings.
- GPU qualification, tuning recommendations, and request planning do not share a single canonical parameter envelope.
- Model selection is rank-based, not evidence-based.

## Priority Follow-Up Questions

1. Which model pairs are actually safe and beneficial for speculative decoding on Pocket GPT target devices?
2. What backend-specific GPU batch and ubatch ceilings are defensible for OpenCL and future Hexagon/QNN paths?
3. Which additional parameters should enter the tuning loop first: context, flash attention, thread counts, or routing choice?
4. How should tuning keys encode model version, quantization, context bucket, and backend identity?

## Supporting Notes

- `engineer-routing-config.md`
- `engineer-tuning-recommendations.md`
- `engineer-memory-residency.md`
