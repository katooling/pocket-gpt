# Native Runtime Engineer Note: Backend Capabilities

## Topic

Native build configuration, backend initialization, backend selection, and backend-specific guards.

## Current State

- The Android native build intentionally uses a minimal `llama.cpp` footprint with static ggml backends, OpenCL enabled, Hexagon optional, and CUDA/HIP/Metal/Vulkan/SYCL/RPC disabled. Evidence: `apps/mobile-android/src/main/cpp/CMakeLists.txt:36-69`.
- The build is pinned to `arm64-v8a` and produces six CPU-optimized shared libraries:
  - `pocket_llama`
  - `pocket_llama_v8`
  - `pocket_llama_v8_2`
  - `pocket_llama_v8_2_dotprod`
  - `pocket_llama_v8_2_i8mm`
  - `pocket_llama_v8_2_dotprod_i8mm`
  Evidence: `apps/mobile-android/src/main/cpp/CMakeLists.txt:143-166`, `apps/mobile-android/build.gradle.kts:101-119`.
- The bridge chooses a native library candidate order from `/proc/cpuinfo` feature tokens and falls back to the baseline artifact. Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:833-848`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1642-1664`.
- Native backend selection prefers:
  - explicit `hexagon` if available
  - explicit `opencl` if available
  - in `AUTO`, Hexagon first, then OpenCL, then CPU fallback
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:898-939`.
- Diagnostics are comparatively strong already. Native code exports compiled backends, device counts, device memory, OpenCL version and Adreno generation, active backend, model quantization tag, flash-attention guard reason, quantized-KV guard reason, and last backend error. Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:947-981`.
- The Kotlin bridge adds higher-level policy on top:
  - explicit backend fail-fast for `OPENCL` and `HEXAGON`
  - `AUTO` backend may downgrade to CPU
  - OpenCL quantization demotion heuristic
  Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:390-438`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1156-1188`.

## Evidence

- Native tests cover variant selection fallback and backend-policy behavior at the bridge layer. Evidence: `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt:9-39`, `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt:247-321`, `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt:397-579`.
- OpenCL-specific guards are implemented in two places:
  - flash attention is forcibly disabled when OpenCL is active
  - quantized KV cache is disabled on OpenCL
  Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2836-2860`.

## Risk Or Gap

1. OpenCL quantization compatibility is inferred from filename and model ID strings, not from GGUF metadata or runtime capability queries.
   - This is the clearest native/runtime-policy heuristic in the stack.
   - Wrong filenames or new quant tags can cause false CPU demotion or unsafe GPU attempts.
   Evidence: `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:402-417`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1173-1188`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt:1604-1614`.

2. OpenCL flash-attention and quantized-KV guards are hardcoded.
   - That is defensible as a safety posture, but it means the current implementation may be leaving performance on the table without a qualification path back to enablement.
   Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:2836-2860`.

3. Backend initialization is permissive but not strongly qualified.
   - `ensure_backend_initialized_locked()` always calls `ggml_backend_load_all()` and returns `true`; qualification is inferred later through device enumeration.
   - There is no explicit “backend loaded but unqualified” lifecycle stage.
   Evidence: `apps/mobile-android/src/main/cpp/pocket_llama.cpp:1303-1322`.

4. Hexagon is compile-time optional and appears less exercised than OpenCL in local tests.
   - Most existing tests simulate diagnostics strings rather than proving real device behavior.

## Recommendation

1. Replace string-based OpenCL quant gating with metadata-driven gating.
   - Source the quantization family from GGUF metadata or native model metadata rather than filenames.

2. Split backend status into three layers:
   - compiled in build
   - discovered on device
   - qualified for a model/quant/profile combination

3. Add a backend qualification suite per accelerator.
   - explicit `OPENCL`
   - explicit `HEXAGON`
   - `AUTO`
   - CPU fallback

4. Turn the OpenCL flash/KV guards into documented capability rules with an escape hatch for qualified devices.

## Validation Needed

- Real-device qualification for:
  - backend discovery
  - backend selection
  - OpenCL quantization families
  - flash attention enable/disable behavior
  - quantized KV enable/disable behavior
- Test matrix covering:
  - explicit profile unavailable
  - `AUTO` fallback
  - mixed Hexagon/OpenCL availability
  - wrong or missing quantization tags

## Open Questions

- Can Pocket GPT read quantization identity from its existing GGUF metadata pipeline and remove filename heuristics entirely?
- Which devices actually qualify for Hexagon in the intended launch/support matrix?
- Is there any OpenCL device subset where flash attention or quantized KV can be re-enabled safely?
