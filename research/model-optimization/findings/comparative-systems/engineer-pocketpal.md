# Engineer Note: PocketPal AI

## Topic

PocketPal AI as the closest mobile comparator for Pocket GPT.

## Current State

- PocketPal exposes a broad on-device runtime tuning surface through its React Native model store.
- It has explicit mobile lifecycle handling for auto-offload and foreground reload.
- It persists benchmark results together with runtime initialization settings.
- It couples model download, GGUF metadata capture, and multimodal projection-model handling.

## Evidence

- `pocketpal-ai/src/store/ModelStore.ts`
  - Stores a unified `contextInitParams` object and persists it with MobX.
  - Initializes thread count from device CPU info and sets a recommended thread count automatically.
  - Exposes setters for `n_threads`, `cache_type_k`, `cache_type_v`, `n_batch`, `n_ubatch`, `n_ctx`, `n_gpu_layers`, `image_max_tokens`, `use_mlock`, and `use_mmap`.
  - Applies safety clamping in `getEffectiveContextInitParams()` so batch and uBatch cannot exceed context or batch.
- `pocketpal-ai/src/utils/types.ts`
  - `ContextInitParams` includes `devices`, `flash_attn_type`, `kv_unified`, and `n_parallel` on top of core llama context parameters.
  - `BenchmarkResult` persists `config`, `peakMemoryUsage`, `wallTimeMs`, and `initSettings`, which is useful for reproducing benchmark runs.
  - `BenchmarkConfig` is simple (`pp`, `tg`, `pl`, `nr`, `label`) and easier to compare across runs than a free-form result blob.
- `pocketpal-ai/src/utils/deviceCapabilities.ts`
  - Uses explicit platform gating:
    - iOS: Metal allowed only on iOS 18+.
    - Android: OpenCL allowed only with Adreno GPU plus `i8mm` and `dotprod` CPU features.
  - Also derives a recommended thread count from device core count.
- `pocketpal-ai/src/store/ModelStore.ts:635-682`
  - Implements background auto-release and foreground reload through `checkAndReloadAutoReleasedModel()` and `handleAppStateChange()`.
  - Persists `wasAutoReleased` and `lastAutoReleasedModelId`.
- `pocketpal-ai/docs/getting_started.md:45-72`
  - Documents Metal defaulting on iOS, auto offload/load, and an advanced settings surface.
- `pocketpal-ai/src/store/ModelStore.ts:178-181, 841-907`
  - Fetches GGUF metadata after download and auto-downloads projection models for vision models when needed.

## Risk Or Gap

- PocketPal exposes many low-level runtime levers directly to the app layer. That is powerful, but it also increases the chance of invalid combinations, user confusion, and support burden.
- Its hardware qualification logic is explicit but fairly hard-coded. That is good for predictability, but it may go stale as backend support evolves.
- Some protections are UI-side or store-side rather than enforced deeper in the runtime, which can leave room for drift.

## Recommendation

- Pocket GPT should copy:
  - explicit mobile lifecycle handling for offload and reload
  - persistence of benchmark runtime settings alongside results
  - GGUF metadata capture as part of model management
  - parameter clamping before runtime invocation
- Pocket GPT should avoid:
  - exposing every low-level runtime flag directly to end users
  - relying only on static device heuristics where runtime probing is feasible

## Open Questions

- Which PocketPal safeguards are enforced in `llama.rn` itself versus only in the app store layer?
- How much of the broad tuning surface actually improves outcomes for normal users versus creating configuration noise?
