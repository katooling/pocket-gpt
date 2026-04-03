# Model R&D Failure Matrix

Date: 2026-04-03

## Scope

This note records the outcomes of the `model rnd map` workstreams on the reconnected Snapdragon `SM-S906N` (`taro`, `qcom`, Android `16`) and folds in one small product hardening change made during the investigation.

## Branch Outcomes

### 1. Backend discovery truth

Outcome: stable runtime failure, not a model-tier-specific failure.

Evidence from repeated `RuntimeBackendTruthInstrumentationTest` runs:

- `compiled_backends=opencl`
- `registered_backends=cpu,opencl`
- `discovered_backends=none`
- `active_backend=cpu`
- `native_runtime_supported=false`
- `opencl_icd_source=android_vendor_lib`
- `opencl_icd_filenames=/vendor/lib64/libOpenCL.so`
- `opencl_device_count=0`
- `hexagon_device_count=0`

Interpretation:

- PocketGPT's current native build can register an OpenCL backend, but cannot discover any usable OpenCL platform/device on this phone.
- Standard models still load and generate on CPU.
- Specialized Bonsai loads still fail under strict OpenCL expectations.

### 2. Android packaging / namespace / ICD branch

Outcome: evidence favors loader / namespace / vendor-chain failure over duplicate-library packaging.

What was verified:

- The app APK does **not** package `libOpenCL.so`.
- The androidTest APK packages **no native libraries** of its own.
- `libpocket_llama.so` does **not** `DT_NEEDED` `libOpenCL.so`; it only depends on:
  - `libandroid.so`
  - `liblog.so`
  - `libm.so`
  - `libdl.so`
  - `libc++_shared.so`
  - `libc.so`
- `libpocket_llama.so` exports `clGetPlatformIDs` and embeds the Khronos ICD loader sources.
- The generated CMake cache confirms:
  - `GGML_OPENCL=ON`
  - `GGML_OPENCL_EMBED_KERNELS=ON`
  - `GGML_OPENCL_USE_ADRENO_KERNELS=ON`
  - `GGML_VULKAN=OFF`

Runtime evidence:

- The app process mapped `libvulkan.so`, `libEGL_adreno.so`, and `libGLESv2_adreno.so`.
- The same `/proc/<pid>/maps` capture did **not** show `libOpenCL.so` mapped into the app process.
- Native logs still reported `opencl_icd_source=android_vendor_lib` and `ggml_opencl: platform IDs not available`.

Interpretation:

- The repo is not losing the OpenCL path because of a packaged duplicate `libOpenCL.so`.
- The stronger hypothesis is that the embedded ICD-loader path can see the vendor library path string but still cannot turn that into a usable platform enumeration inside the app process on this device/build combination.

### 3. Artifact and model-semantics branch

Outcome: no evidence that Bonsai artifacts themselves are malformed; one metadata/diagnostics gap was identified and partially fixed.

GGUF dump results for `Bonsai-1.7B.gguf` and `Bonsai-4B.gguf`:

- `general.architecture = qwen3`
- `general.quantization_version = 2`
- `general.file_type = 41`
- valid tokenizer/chat-template metadata present
- valid block-count and context-length metadata present

Interpretation:

- The investigated Bonsai tiers look structurally coherent and consistent with the specialized format family expected by the current runtime work.
- The blocker remains backend/runtime-side, not obviously artifact-header corruption.

Important diagnostics caveat:

- During the specialized-load stage, native diagnostics still reported `active_model_quantization=q4_0` after a standard-model run.
- That means current backend diagnostics describe the **active/previously loaded** model state better than the **requested artifact** under failure.
- Future evidence collection should log requested artifact format separately from active runtime format.

Product hardening completed during this branch:

- App-owned seeded/imported models now persist GGUF sidecars, not just downloaded models.
- Verified on-device: seeded `qwen3.5-0.8b-q4-q4_0.gguf.meta.json` now contains a populated `gguf` block with architecture, dimensions, attention, tokenizer, and context metadata.

Why it matters:

- Without this, non-download paths could fall back to weaker memory-estimation and metadata behavior even when the GGUF header was perfectly readable.

### 4. Process timing / state contamination branch

Outcome: failure is stable across cold and warm runs.

Experiment:

1. Force-stop app and test packages.
2. Run backend-truth instrumentation (`cold`).
3. Run the same instrumentation again immediately (`warm`).

Observed:

- Cold and warm runs both reported `opencl_device_count=0`.
- Cold and warm runs both reported `native_runtime_supported=false`.
- Cold and warm runs both rejected `bonsai-1.7b-q1_0_g128` under the specialized OpenCL path.
- CPU control generation succeeded in both runs.
- Latencies varied modestly (`first_token_ms` ~`3564` vs `4399`), but backend truth did not change.

Interpretation:

- This is not primarily a stale-cache, warmup-order, or one-time-process-init artifact.
- The backend discovery failure appears stable for this device/build shape.

### 5. Policy drift / enforcement gaps

Outcome: drift risk is real and still centered below the UI boundary.

What is currently true:

- `ModelSheet.kt` uses `eligibility.downloadAllowed` and `eligibility.loadAllowed` to disable UI actions.
- `ModelProvisioningViewModel.kt` computes eligibility for presentation, but its action methods still call the gateway directly:
  - `importModelFromUri()`
  - `setActiveVersion()`
  - `loadInstalledModel()`
  - `enqueueDownload()`
- `ProvisioningGateway.kt` is a pass-through adapter with no shared eligibility enforcement.
- `AppRuntimeDependencies.kt` also forwards these operations directly to the store, download manager, or lifecycle coordinator.
- `ModelDownloadManager.enqueueDownload()` validates duplicates and storage, but not model/device/backend policy.
- `AppRuntimeLifecycleCoordinator.loadInstalledModel()` validates presence and memory estimate, then activates and loads; it does not re-run catalog eligibility policy first.
- `loadLastUsedModel()` simply reloads the stored last-used version through the same path.

Interpretation:

- The UI is better informed than the service layer.
- A path blocked in `ModelSheet` can still be reached indirectly through non-UI entry points.
- That drift will become more dangerous as the app adds:
  - direct Hugging Face sources
  - local import-first flows
  - deep-link or automation entry points
  - future background/autoload behavior

## Final Classification

- `backend-truth-matrix`: runtime/backend failure confirmed
- `android-loader-branch`: likely Android/OpenCL loader or namespace bug, not duplicate APK packaging
- `artifact-semantics-branch`: artifacts look coherent; metadata-sidecar path was incomplete for non-download flows and is now improved
- `state-contamination-branch`: stable failure, not process-order noise
- `policy-drift-branch`: UI and lower-layer enforcement still diverge materially

## Recommended Next Steps

### Highest priority

1. Add an explicit backend-capability snapshot model that records compiled, discovered, and qualified backend families separately.
2. Move eligibility enforcement below UI presentation:
   - download scheduling
   - import admission
   - activation/default-version mutation
   - explicit load
   - last-used autoload
3. Add a minimal native OpenCL proof surface that logs:
   - attempted vendor library path
   - actual loader success/failure reason
   - platform count
   - device count
   - whether the vendor library ever becomes mapped

### Important but second-order

1. Restore multi-backend reasoning in architecture work instead of treating "GPU available" as equivalent to "OpenCL available".
2. Separate requested artifact format diagnostics from active-runtime diagnostics.
3. Extend the source model so imported/downloaded artifacts share one admission and policy path.

## Bottom line

The current Bonsai blocker is still primarily a backend discovery/runtime issue on Android, not a Bonsai-tier qualification issue. The app also still has a real enforcement-gap problem: UI eligibility is more advanced than the service/gateway layer that actually admits, activates, downloads, and loads models.
