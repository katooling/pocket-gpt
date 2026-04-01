# Native Runtime Engineer Note: Runtime Parameters

## Topic

Load-time and generation-time parameter handling in the native runtime and JNI bridge.

## Current State

Pocket GPT already exposes a fairly rich native parameter surface.

- The Kotlin contracts expose threads, batch sizes, context size, GPU layers, backend preference, flash attention mode, KV cache types, speculative decoding controls, `mmap`, `mlock`, and `nKeep` in `RuntimeGenerationConfig` and `RuntimeLoadConfig`. Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt:68-155`.
- The JNI bridge forwards that surface almost one-for-one into `nativeLoadModel()` and hot-updates sampler parameters via `nativeSetSamplingConfig()`. Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:66-99`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:471-526`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1306-1468`.
- Native defaults and clamps are hard-coded:
  - context default `2048`
  - batch default `512`
  - prompt decode batch default `512`
  - threads fallback bounded to `2..8`
  - explicit thread requests clamped to `1..16`
  - context clamped to `512..32768`
  - batch clamped to `32..2048`
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:32-34`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:153-175`.
- The bridge has GPU-layer retry logic when strict GPU offload is disabled. The retry ladder is fixed at `100%`, `75%`, `50%`, `25%`, then `0`, and draft-model GPU layers scale proportionally. Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:436-526`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1115-1154`.
- Native load performs warmup for both the main model and, when enabled successfully, the draft model. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2900-2908`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:3011-3022`.
- Sampling is not barebones. The native sampler chain supports penalties, top-k, typical-p, top-p, min-p, XTC, temperature, greedy mode, and both Mirostat variants. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1783-1870`.
- Speculative decoding is parameterized and adaptive. The runtime tracks acceptance rate and adjusts max draft tokens dynamically. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:130-133`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:183-209`.

## Evidence

- Existing bridge tests cover parameter forwarding for flash attention, KV cache types, `mmap`, GPU retry backoff, and hot sampler reconfiguration. Evidence: `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt:76-145`, `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt:247-321`, `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt:327-357`.
- The native load path resolves and clamps parameters internally before building contexts and samplers. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2828-3051`.

## Risk Or Gap

1. Several important values are still fixed heuristics rather than device- or model-class tuned values.
   - `DEFAULT_CONTEXT_SIZE = 2048`
   - `DEFAULT_BATCH_SIZE = 512`
   - threads fallback limited to at most `8`
   - explicit threads capped at `16`
   Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:32-34`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:153-175`.

2. GPU retry backoff is deterministic but coarse.
   - It does not use model metadata, backend heap estimates, or prior benchmark evidence.
   - It can still waste load attempts on obviously oversized configurations.
   Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:436-526`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1115-1154`.

3. The speculative path is clearly heuristic-tuned, not benchmark-tuned.
   - draft `n_ctx` is hard-capped to `1024`
   - draft sampler halves `top_k` and narrows `top_p`
   - draft threads use `nThreadsBatch` for both thread fields
   Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2972-3010`.

4. The current tests mostly prove parameter plumbing, not runtime quality.
   - There is no evidence in `packages/native-bridge` tests of device-backed validation for first-token latency, decode throughput, or RSS behavior under different parameter combinations.

## Recommendation

1. Introduce a device-class tuning table for native defaults:
   - CPU thread targets
   - `n_batch` / `n_ubatch`
   - `n_ctx`
   - `n_keep`
   - speculative draft limits

2. Replace the fixed GPU retry ladder with an estimate-informed ladder.
   - Feed the runtime’s `estimateMaxGpuLayers()` result and benchmark-derived safety margins into the first attempt.

3. Treat speculative parameters as an optimization profile, not constants.
   - Benchmark the current `draft_n_ctx=1024`, `top_k/2`, and narrowed `top_p` choices before keeping them.

4. Add native performance evidence requirements for every parameter profile change:
   - first-token latency
   - decode TPS
   - peak RSS
   - success rate across device tiers

## Validation Needed

- Benchmark sweeps across:
  - `n_threads`
  - `n_threads_batch`
  - `n_batch`
  - `n_ubatch`
  - `n_ctx`
  - GPU retry ladder shape
  - speculative draft limits
- Device-backed comparison of strict offload, auto fallback, and CPU-only profiles.

## Open Questions

- What should the default thread cap be for current flagship Android SoCs?
- Should the first GPU-layer attempt be taken from `estimateMaxGpuLayers()` rather than from the user-requested value directly?
- Is `draft_n_ctx = min(target_n_ctx, 1024)` still the right tradeoff for the target model set?
