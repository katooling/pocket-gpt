# Native Runtime Engineer Note: Cache And Speculative Paths

## Topic

Prefix cache, disk session cache, context-shift handling, and speculative decoding behavior in the native runtime.

## Current State

- The native runtime keeps a two-slot prefix cache with:
  - target prompt tokens
  - draft prompt tokens
  - target sequence state
  - draft sequence state
  - cache key
  - LRU-ish epoch tracking
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:59-94`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1363-1482`.
- Prefix-cache telemetry is reasonably rich:
  - hits/misses
  - store/restore success/failure
  - over-budget counts
  - last slot/reuse info
  - context-shift and context-rebuild counters
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1527-1561`.
- Generation reuses target and draft sequence state when possible, falls back cleanly when reuse fails, and captures fresh state after generation. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2239-2658`.
- Long prompts and decode overflow are handled through:
  - prompt trimming
  - context shift
  - full context rebuild when shift is unavailable
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:367-618`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2291-2527`.
- Disk-backed session cache exists and is wired through the bridge, but the serialized format stores only:
  - target prompt tokens
  - cache key
  - target sequence state
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1621-1731`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:3605-3644`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:242-255`.
- Speculative decoding is implemented with:
  - draft model load
  - separate draft context
  - adaptive acceptance tracking
  - sampler narrowing for the draft model
  - draft cache reuse and persistence
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:183-209`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2387-2658`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2939-3049`.

## Evidence

- The bridge exposes prefix-cache diagnostics and session-cache APIs, but local tests only assert delegation or string passthrough for the cache diagnostics line. Evidence: `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/LlamaCppInferenceModuleTest.kt:142-147`, `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt:628-644`.
- I did not find native-bridge tests covering:
  - save/load session cache compatibility
  - prefix-cache restore correctness
  - strict vs non-strict cache key semantics
  - speculative decode correctness/perf behavior on real runtimes

## Risk Or Gap

1. Disk session cache compatibility is under-specified.
   - The file format does not persist model identity, model version, quantization, backend, `n_ctx`, KV-cache config, tokenizer signature, or prompt-template compatibility.
   - That means a stale cache can be restored into a materially different runtime configuration.
   Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1621-1731`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:3624-3644`.

2. The two-slot cache is probably a conservative MVP, not a tuned answer.
   - It may be fine for single-session reuse, but it is likely too small for multi-session or model-switch workflows.
   Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:91-94`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1378-1426`.

3. Cache-state budgets are fixed at `192 MiB` target and `48 MiB` draft.
   - Over-budget failures are logged and counted, but there is no policy loop tuning these values.
   Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:59-60`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1564-1619`.

4. Speculative decode failure observability is coarse.
   - The code logs `SPECULATIVE|disabled|reason=draft_init_failed`, but multiple distinct causes collapse into that message after cleanup.
   Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:3025-3038`.

5. Long-context behavior is implemented but not obviously validated.
   - Prompt trimming, context shift, and rebuild logic are present, but I did not find matching correctness benchmarks or tests proving acceptable behavior for long prompts on device.

## Recommendation

1. Extend the session-cache file header to include runtime compatibility metadata:
   - model ID
   - model version/path hash
   - quantization
   - `n_ctx`
   - backend profile
   - tokenizer/template signature

2. Add integration tests for cache correctness:
   - matching model/settings restores
   - mismatched model/settings reject restore
   - strict vs non-strict key behavior
   - over-budget state behavior

3. Make speculative failure reasons explicit in telemetry.
   - vocab mismatch
   - draft load OOM
   - draft context init OOM
   - sampler creation failure
   - disabled by configuration

4. Benchmark whether two cache slots and the current state-size caps are sufficient for expected UX flows.

## Validation Needed

- Native/device-backed tests for:
  - session-cache save/load compatibility
  - prefix-cache hit correctness after restore
  - long-prompt context shift/rebuild correctness
  - speculative decode latency/TPS wins and failure rates
- Telemetry review on real devices for:
  - cache over-budget frequency
  - cache hit rate
  - speculative acceptance rate by model and device

## Open Questions

- What compatibility metadata is minimally required before a saved session cache is safe to restore?
- Is the current two-slot design enough for the intended chat/session UX?
- Which models actually benefit from speculative decoding on the supported Android device classes?
