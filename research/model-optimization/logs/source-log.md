# Senior Lead Source Log

Date accessed: 2026-03-31

## Official Runtime And Platform Sources

### ggml-org/llama.cpp README

- URL: https://github.com/ggml-org/llama.cpp
- Why it matters:
  - documents the official feature surface for backends, quantization, benchmarking, and speculative decoding
  - useful baseline for checking whether Pocket GPT is using supported mechanisms correctly
- Key notes:
  - official README lists broad backend and quantization support
  - official `llama-server` examples include speculative decoding with `-md draft.gguf`
  - current README still lists OpenCL for Adreno and Hexagon as in progress for Snapdragon

### LM Studio Docs: Speculative Decoding

- URL: https://lmstudio.ai/docs/app/advanced/speculative-decoding
- Why it matters:
  - practical guidance on draft-model compatibility and when speculative decoding helps or hurts
  - useful for translating theory into product behavior and parameter selection
- Key notes:
  - draft model must share vocabulary with the main model
  - performance depends on draft model quality, size gap, and prompt characteristics

### Qualcomm Snapdragon 8 Gen 3 Product Brief

- URL: https://docs.qualcomm.com/bundle/publicresource/87-71408-1_REV_B_Snapdragon_8_gen_3_Mobile_Platform_Product_Brief.pdf
- Why it matters:
  - relevant device-class context for Android GPU and NPU capabilities
  - useful for understanding real backend targets Pocket GPT may encounter on modern Snapdragon phones
- Key notes:
  - product brief lists OpenCL 3.0 FP and Vulkan 1.3 support on Adreno GPU
  - product brief lists Qualcomm AI Engine with Hexagon NPU and mixed-precision support

### Additional Current Cross-Checks

- `llama.cpp` `llama-bench` README: batch, ubatch, cache-type-k/v, threads, and GPU-layer sweeps remain first-class benchmark axes.
- `llama.cpp` `quantize` README: `--imatrix` is still documented as highly recommended.
- `llama.cpp` Snapdragon backend README: Snapdragon supports CPU, GPUOpenCL, and Hexagon backends, with Hexagon still marked experimental.
- LM Studio speculative decoding docs: compatible draft models must share vocabulary with the main model.

## Research Papers

### Fast Inference from Transformers via Speculative Decoding

- URL: https://arxiv.org/abs/2211.17192
- Why it matters:
  - foundational speculative decoding paper
  - useful baseline for judging whether Pocket GPT's speculative path is aligned with the core algorithm and expected tradeoffs

### KIVI: A Tuning-Free Asymmetric 2bit Quantization for KV Cache

- URL: https://arxiv.org/abs/2402.02750
- Why it matters:
  - directly relevant to KV-cache optimization under long context or larger batching
  - useful for deciding whether Pocket GPT should remain conservative with current KV quantization or explore more advanced paths
