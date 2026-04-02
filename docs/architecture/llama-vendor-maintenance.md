# `llama.cpp` Vendor Maintenance

PocketGPT integrates native inference through the vendored `third_party/llama.cpp` submodule and the Android seam in `apps/mobile-android/src/main/cpp/CMakeLists.txt`.

## Vendor stack

Treat the runtime as a layered stack:

1. `ggml-org/llama.cpp` upstream baseline
2. PrismML Bonsai format support (`Q1_0` / `Q1_0_g128`)
3. PocketGPT-specific overlay patches

The app should depend on the vendored submodule contents, not on assumptions about which upstream fork was used to produce them.

## Current PocketGPT overlay

PocketGPT carries local runtime changes on top of vendored `llama.cpp`, including:

- `52e654729` — Android regex stability and Phi tokenizer support
- `37fea2efc` — optional rotation hook in KV cache
- `0670b510a` — TurboQuant Q rotation and inverse rotation
- `9930c7819` — refine TurboQuant rotation and KV-cache output

For Bonsai support, PocketGPT also imports PrismML's 1-bit quantization work:

- `59f2b8485` — add `Q1_0` and `Q1_0_g128` support
- `1b0fadf46` — simplify `Q1_0_g128` dequantization

## Refresh workflow

When updating the vendor:

1. Start from the desired upstream `llama.cpp` revision.
2. Replay or re-import the minimal PrismML Bonsai support commits needed for `Q1_0_g128`.
3. Replay the PocketGPT overlay commits.
4. Keep Android integration changes in `pocket_llama.cpp` and `CMakeLists.txt` small and explicit.
5. Verify that native diagnostics still expose `supports_q1_0` and `supports_q1_0_g128`.

If a refresh makes the PrismML commits obsolete because upstream adds equivalent 1-bit support, prefer the upstream implementation and remove the now-redundant overlay.

## Bridge support rule

Do not set `ModelCatalog.ModelDescriptor.bridgeSupported = true` only because a model is conceptually compatible with `llama.cpp`.

Set `bridgeSupported = true` only when all are true:

- the vendored native runtime can parse the model artifact format
- the Kotlin/native bridge exposes any required runtime capability flags
- a real JNI/device-path test proves the model loads successfully

Catalog-only or fake-bridge tests are not sufficient evidence for bridge support.

## Required validation for Bonsai-like models

Before shipping a model that depends on a specialized quantization/runtime fork:

- run `bash scripts/dev/test.sh fast`
- run a real Android instrumentation test that seeds the artifact path and performs a JNI load
- confirm the runtime diagnostics payload reports the expected format support flags

This prevents the UI from advertising models that the vendored native runtime cannot actually load.
