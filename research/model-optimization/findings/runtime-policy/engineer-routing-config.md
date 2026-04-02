# Runtime Policy Engineer Notes: Routing And Config Mapping

## Scope

- `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/AdaptiveRoutingPolicy.kt`
- `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/ModelCatalog.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ChatRuntimeService.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt`

## Findings

### 1. Base runtime policy is mostly static presets with only coarse device heuristics layered on top

Evidence:

- `PerformanceRuntimeConfig.forProfile(...)` hard-codes per-profile defaults for threads, batch, ubatch, context, sampler settings, speculative decoding, mmap, and `nKeep`, with only three profiles and no model-family specialization: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt:68-176`
- `AdaptiveRoutingPolicy` only considers `ramClassGb`, `batteryPercent`, `thermalLevel`, and fixed descriptor ranks to choose a model and context budget: `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/AdaptiveRoutingPolicy.kt:4-60`

Why it matters:

- The policy stack is acting more like a fixed preset table than an adaptive optimizer.
- This is likely acceptable for a bootstrap implementation, but it is weak as the control plane for model optimization across heterogeneous Android devices.

### 2. GPU batch sizing is internally inconsistent between profiles and the request planner

Evidence:

- Profile defaults set `BALANCED` to `nBatch=512`, `nUbatch=512`, and `FAST` to `768/768`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt:88-104`
- `ChatStreamRequestPlanner` clamps every GPU-backed request to `GPU_SAFE_BATCH = 256`, regardless of the higher profile defaults or tuner recommendations: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ChatRuntimeService.kt:134-173`

Why it matters:

- The system advertises higher base values, but the request planner silently cuts them down for GPU runs.
- That means the profile layer, tuner, and actual runtime request path are not operating on the same parameter surface.

Recommendation:

- Make the GPU-safe batch rule explicit in the profile layer or replace it with backend-specific validated limits.
- Otherwise the team will tune values that never actually reach inference.

### 3. Speculative decoding policy is not model-family aware

Evidence:

- All non-`BATTERY` profiles default speculative decoding on and force the same draft model id: `ModelCatalog.SMOLLM3_3B_UD_IQ2_XXS`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt:142-150`
- The later gate only checks `ramClassGb >= 8`, non-empty draft id, a registered draft path, and that the draft path is not identical to the target path: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt:356-377`

Why it matters:

- The policy layer does not verify tokenizer or vocabulary compatibility, model family pairing, or that the draft is actually smaller and faster than the target.
- This is a concrete gap between the optimization mechanism and the policy that enables it.

Recommendation:

- Add an explicit compatibility registry for speculative pairs.
- Do not enable speculative decoding from a profile default alone.

### 4. Routing and context budgeting are not tightly integrated with the effective prompt budget

Evidence:

- `AdaptiveRoutingPolicy.selectContextBudget(...)` returns up to `8192` tokens for `"long_text"`: `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/AdaptiveRoutingPolicy.kt:26-36`
- `SendMessageUseCase` converts that into a prompt-char budget via `minOf(contextBudget * 4, MAX_PROMPT_CHARS_LONG)`, where `MAX_PROMPT_CHARS_LONG = 2048`: `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/SendMessageUseCase.kt:74-96`, `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/SendMessageUseCase.kt:314-318`

Why it matters:

- The routing layer may believe it is granting large-context behavior while the prompt builder is still aggressively char-capped.
- That weakens the value of context-related optimization decisions and can hide context regressions.

### 5. Model selection policy is rank-based, not evidence-based

Evidence:

- `ModelCatalog` stores `qualityRank`, `speedRank`, `fallbackPriority`, and `minRamGb` as static descriptor metadata: `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/ModelCatalog.kt:19-210`
- `AdaptiveRoutingPolicy` chooses by sorting those ranks; it does not use measured throughput, first-token latency, crash rate, or device-specific historical data: `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/AdaptiveRoutingPolicy.kt:38-60`

Why it matters:

- The code has a place to select different models, but not a feedback loop to improve those selections from real device evidence.
- For an optimization program, this is one of the main missing control loops.

## Open Questions

1. Should routing be model-family aware first, device-cluster aware second, or the reverse?
2. Should speculative decoding be treated as a model-pair feature instead of a profile feature?
3. Is the `GPU_SAFE_BATCH = 256` clamp still necessary for all GPU backends, or is it carrying forward a narrower OpenCL limitation?
