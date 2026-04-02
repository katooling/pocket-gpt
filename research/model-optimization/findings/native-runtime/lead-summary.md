# Native Runtime Lead Summary

Status: Initial evidence-backed pass complete.

## Scope

- `apps/mobile-android/src/main/cpp/pocket_llama.cpp`
- `apps/mobile-android/src/main/cpp/CMakeLists.txt`
- `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/*`

## Existing Optimizations Inventory

The native runtime already contains a substantial optimization surface:

- Minimal Android `llama.cpp` build with static backends, OpenCL enabled, Hexagon optional, and multiple CPU-tuned arm64 library variants. Evidence: `apps/mobile-android/src/main/cpp/CMakeLists.txt:36-69`, `apps/mobile-android/src/main/cpp/CMakeLists.txt:143-166`.
- CPU feature-based shared-library selection in the JNI bridge, with fallback to the baseline library. Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:833-848`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1642-1664`.
- Backend discovery, selection, and diagnostics for `AUTO`, `HEXAGON`, `OPENCL`, and `CPU`. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:791-1048`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt:13-18`.
- GPU-load retry backoff in the Kotlin bridge when strict GPU offload is disabled. Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:436-526`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1115-1154`.
- Runtime parameter support for:
  - threads
  - batch / ubatch
  - context size
  - flash attention mode
  - split KV cache types
  - `mmap` / `mlock`
  - speculative draft settings
  - `nKeep`
  Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt:50-155`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2828-3051`.
- Prefix cache with target/draft sequence-state capture and restore, plus disk-backed session-cache persistence. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1363-1731`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2239-2658`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:3605-3644`.
- Context-shift and rebuild logic for long prompts and decode overflow. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:428-618`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2291-2527`.
- Speculative decoding with a draft model, adaptive acceptance-rate tracking, and draft sampler narrowing. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:183-209`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2939-3049`.

## Highest-Confidence Findings

### 1. OpenCL quantization gating is heuristic and string-based

This is the clearest fragile path in the current native/runtime stack.

- The bridge demotes OpenCL GPU layers to CPU based on regex matches against the filename stem and model ID, not on GGUF metadata or a runtime capability query. Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:402-417`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1173-1188`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1604-1614`.
- The native layer separately extracts a quantization tag from the filename only for diagnostics, which reinforces that quant identity is still string-derived. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:703-738`.

Why this matters:

- safe quants can be falsely demoted if naming changes
- unsafe quants can slip through if naming is incomplete
- the mechanism is brittle relative to the actual model artifact

### 2. OpenCL safety guards are probably conservative, but they are still hardcoded guards

- Flash attention is forcibly disabled whenever GPU ops are active on OpenCL. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2836-2839`.
- Quantized KV cache is disabled on OpenCL and also disabled whenever flash attention is disabled. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2840-2860`.

Interpretation:

- this is a plausible safety workaround
- it is also a likely performance ceiling until there is a qualification path to re-enable features on proven-safe devices

### 3. Session-cache persistence has a correctness gap

- The disk session-cache format stores prompt tokens, cache key, and target sequence state, but no model identity, version, backend, context size, quantization, or tokenizer/template signature. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1621-1731`.
- Loading a saved session cache just loads it into a selected prefix slot and marks it active; there is no compatibility verification at load time. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:3624-3644`.

This is the most concrete correctness risk I found in the caching path.

### 4. The parameter stack is rich, but much of it is still heuristic rather than benchmark-calibrated

- Defaults and clamps are fixed:
  - `n_ctx = 2048`
  - `n_batch = 512`
  - fallback thread count capped at `8`
  - explicit threads capped at `16`
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:32-34`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp:153-175`.
- GPU retry backoff uses a fixed `100%/75%/50%/25%/0` ladder. Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1115-1154`.
- Speculative decode uses heuristic choices like `draft_n_ctx <= 1024`, `top_k / 2`, and narrowed `top_p`. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2972-3010`.

I do not see evidence here yet that these values are benchmark-derived or device-class tuned.

### 5. Validation coverage is thinner than the mechanism surface

What is covered locally:

- bridge-level parameter forwarding
- backend-policy decisions
- GPU retry behavior
- diagnostics passthrough

What I did not find in local tests:

- session-cache compatibility tests
- native prefix-cache correctness tests
- long-context shift/rebuild correctness tests
- real-runtime speculative decode validation

Evidence: `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt:9-579`, `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/LlamaCppInferenceModuleTest.kt:142-147`.

## Missing Optimizations Or Missing Validation

The highest-priority missing pieces are not entirely new mechanisms; they are missing evidence and missing hardening:

1. Metadata-driven backend/quant compatibility instead of filename heuristics.
2. Device-class tuning for threads, batch sizes, context size, and speculative limits.
3. Qualification matrix for:
   - `AUTO`
   - explicit `OPENCL`
   - explicit `HEXAGON`
   - CPU fallback
4. Session-cache compatibility metadata and tests.
5. Better speculative failure telemetry than the current generic `draft_init_failed`.
6. Device-backed validation for prefix cache, long-context shift/rebuild, and speculative decode.

## Top-Priority Follow-Up Questions

1. Can Pocket GPT use GGUF metadata as the source of truth for quantization compatibility and remove filename-based OpenCL gating?
2. Which Android device classes are actually in scope for Hexagon qualification versus OpenCL-only or CPU-only fallback?
3. What parameter table should govern:
   - `n_threads`
   - `n_threads_batch`
   - `n_batch`
   - `n_ubatch`
   - `n_ctx`
   - speculative draft limits
4. What compatibility metadata must be written into the session-cache format before it is safe to restore across loads?
5. Are the OpenCL flash-attention and quantized-KV guards permanent constraints, or temporary constraints awaiting qualification data?

## Blocker

The only material blocker in this child context was subagent fanout.

- The child context did not expose the `spawn_agent` tool, so I could not create the required second-level engineer subagents directly from this lead context.
- I preserved the planned work split by writing the engineer-owned notes manually in disjoint files:
  - `engineer-runtime-parameters.md`
  - `engineer-backend-capabilities.md`
  - `engineer-cache-speculative.md`

This blocked hierarchical delegation, not the evidence pass itself.
