# External Research Engineer: Mobile Backends

Date accessed: 2026-03-31

## Scope

Primary-source guidance relevant to Pocket GPT's Android OpenCL and Hexagon paths.

## Source Register

| Source | URL | Why it matters |
|------|-----|----------------|
| llama.cpp Snapdragon backend README | https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md | Official upstream guidance for Android Snapdragon builds, backend selection, HTP sessions, and experimental Hexagon features. |
| llama.cpp README | https://github.com/ggml-org/llama.cpp/blob/master/README.md | Confirms upstream backend support targets: OpenCL on Adreno and Hexagon on Snapdragon. |
| Khronos OpenCL 3.0 unified spec | https://registry.khronos.org/OpenCL/specs/3.0-unified/html/OpenCL_API.html | Shows that many OpenCL 3.0 language and device capabilities are optional/queryable, which matters for runtime probing on mobile GPUs. |
| Qualcomm TensorFlow Lite SDK Tools Quick Start Guide | https://docs.qualcomm.com/bundle/publicresource/80-50450-52_REV_AB_Qualcomm_TensorFlow_Lite_SDK_Tools_Quick_Start_Guide.pdf | Supplemental Qualcomm evidence that Android inference stacks commonly require explicit backend/delegate selection for HTP and GPU. |

## Findings

### 1. Upstream llama.cpp explicitly treats Snapdragon as a multi-backend target

The Snapdragon README documents building with:

- CPU
- OpenCL
- Hexagon

and says the runtime supports three backends on Snapdragon devices:

- CPU
- Adreno GPU (`GPUOpenCL`)
- Hexagon NPU (`HTP0-4`)

Implication for Pocket GPT:

- backend selection is not a binary CPU-vs-GPU toggle
- a good Android inference policy should reason about backend family, available sessions, and model size

### 2. Hexagon is still documented as experimental and flash attention is explicitly gated

The Snapdragon README marks Hexagon as experimental in example output and states:

- `GGML_HEXAGON_EXPERIMENTAL=1` is required for experimental features
- `FLASH_ATTN_EXT` is one of those experimental ops

Implication for Pocket GPT:

- Hexagon flash attention should stay behind strict qualification
- "backend works" and "flash attention is safe on that backend" must be treated as separate states

### 3. Session count and repack memory are first-order constraints on Snapdragon

The Snapdragon README says:

- most quantized models under 4B fit into one Hexagon session
- an 8B model needs two sessions
- a 20B model needs four

It also shows large `HTP*-REPACK` allocations in example runs.

Implication for Pocket GPT:

- model tiering should account for session count and repack memory, not just GGUF file size
- backend qualification should be model-specific, not only device-specific

### 4. Official Snapdragon examples use q8_0 KV caches on Hexagon

The official OLMoE example reports:

- `K (q8_0)`
- `V (q8_0)`

Implication for Pocket GPT:

- `q8_0` is the clearest upstream example baseline for KV-cache quantization on Hexagon
- more aggressive KV settings should be treated as research candidates rather than default settings

### 5. OpenCL version labels do not guarantee feature parity

The Khronos OpenCL 3.0 spec notes that many OpenCL C 2.0-era features became optional in OpenCL 3.0 and must be queried through device capability mechanisms.

Implication for Pocket GPT:

- runtime probing matters more than nominal version strings
- Pocket GPT should not infer subgroup, non-uniform work-group, generic address-space, or similar support from "OpenCL 3.0" alone
- the existing strategy of targeting a conservative OpenCL language level for compatibility is defensible, but it should be paired with richer runtime diagnostics

### 6. Qualcomm's Android SDK examples also reinforce explicit backend selection

The Qualcomm TFLite SDK guide shows backend-specific delegate configuration such as:

- `backend_type:htp`
- GPU delegate usage

This is not Pocket GPT's exact stack, but it reinforces the same design principle:

- backend identity and backend configuration are explicit deployment concerns on Android AI stacks

## Immediate Recommendations For Pocket GPT

1. Keep Hexagon opt-in and qualification-driven.
2. Gate flash attention separately from basic Hexagon enablement.
3. Start mobile KV-cache validation with `F16` vs `Q8_0` before lower-bit experiments.
4. Add backend memory diagnostics for:
   - host memory
   - repack memory
   - KV-cache memory
   - required session count
5. Keep OpenCL compatibility targeting conservative, but add deeper runtime feature reporting rather than assuming version capability.
