# Engineer Note: LM Studio, lms, and MLX Engine

## Topic

LM Studio and MLX as comparators for typed configuration, speculative decoding, cache quantization, and observability.

## Current State

- LM Studio has a cleaner typed separation between load-time configuration and prediction-time configuration than the other comparators.
- Its SDK exposes draft-model and speculative settings as part of normal model use rather than as hidden implementation details.
- MLX Engine shows concrete cache-management behavior for speculative decoding, prompt-cache reuse, and KV-cache quantization.

## Evidence

- `lmstudio-js/packages/lms-client/src/modelShared/ModelNamespace.ts`
  - `BaseLoadModelOpts` separates `config`, `ttl`, `deviceIdentifier`, `signal`, and `onProgress`.
  - This is a strong model lifecycle surface for loading/unloading and progress reporting.
- `lmstudio-js/packages/lms-shared-types/src/llm/LLMLoadModelConfig.ts`
  - Provides typed load-time fields for:
    - GPU offload ratio and split strategy
    - `maxParallelPredictions`
    - `useUnifiedKvCache`
    - `offloadKVCacheToGpu`
    - `contextLength`
    - `evalBatchSize`
    - `flashAttention`
    - `keepModelInMemory`
    - `tryMmap`
    - `tryDirectIO`
    - llama KV cache quantization
    - MLX KV cache quantization
- `lmstudio-js/packages/lms-shared-types/src/llm/LLMPredictionConfig.ts`
  - Provides typed prediction-time fields for:
    - `maxTokens`
    - `temperature`
    - `contextOverflowPolicy`
    - `topKSampling`
    - `topPSampling`
    - `minPSampling`
    - `repeatPenalty`
    - `presencePenalty`
    - `cpuThreads`
    - `draftModel`
    - speculative-decoding tuning fields
    - `reasoningParsing`
- `lmstudio-js/packages/lms-client/src/llm/PredictionResult.ts`
  - Returns `stats`, `modelInfo`, `loadConfig`, and `predictionConfig` with every prediction result.
  - This is stronger observability than a plain text response.
- `lmstudio-js/packages/lms-client/src/llm/LLMDynamicHandle.ts:1186-1245`
  - Has `unstable_preloadDraftModel()` as a best-effort optimization and `getLoadConfig()` / `getBasePredictionConfig()` accessors.
- `lmstudio-js/packages/lms-client/src/llm/LLM.speculativeDecoding.heavy.test.ts`
  - Demonstrates normal SDK usage with `draftModel`.
  - Expects stats to include `numGpuLayers` and `usedDraftModelKey`.
- `mlx-engine/demo.py` and `mlx-engine/batched_demo.py`
  - Expose `--draft-model`, `--num-draft-tokens`, `--max-kv-size`, `--kv-bits`, `--kv-group-size`, `--quantized-kv-start`, and `--parallel`.
  - Include benchmark iterations and accepted draft-token reporting.
- `mlx-engine/mlx_engine/cache_wrapper.py`
  - Tracks prompt tokens and trims cache back to the common prefix instead of always resetting.
  - Quantizes KV cache during prefill via `maybe_quantize_kv_cache(...)`.
  - Uses chunked prefill with progress reporting and cancellation support.
  - Clears and rebuilds cache layout when a draft model is set, combining main-model and draft-model caches in one structure.
- `lms/README.md`
  - Adds simple model lifecycle commands (`ls`, `ps`, `load`, `unload`) and machine-readable JSON output.
  - Useful more as operational ergonomics than as an optimization mechanism.

## Risk Or Gap

- LM Studio’s surface area is richer than Pocket GPT should expose directly in the mobile UI.
- Some LM Studio SDK features are marked unstable or deprecated, so copying the exact public API would be risky.
- MLX-specific engine behavior is not portable to Android, even when the high-level ideas are good.

## Recommendation

- Pocket GPT should copy:
  - a typed split between load-time config and prediction-time config
  - result objects that retain applied load config, prediction config, and runtime stats
  - draft-model preloading as an optimization rather than a correctness dependency
  - prompt-cache trimming and chunked prefill progress reporting
  - explicit KV-cache quantization settings that can be validated per backend
- Pocket GPT should avoid:
  - mirroring the full SDK/API surface into the app UI
  - copying MLX-specific mechanics instead of abstracting the transferable principle

## Open Questions

- Which LM Studio config concepts should stay internal policy knobs versus user-facing controls in Pocket GPT?
- Can Pocket GPT implement LM Studio style observability without coupling the app to unstable raw-config concepts?
