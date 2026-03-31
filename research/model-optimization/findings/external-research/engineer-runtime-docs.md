# External Research Engineer: Runtime Docs

Date accessed: 2026-03-31

## Scope

Official runtime and library documentation most relevant to Pocket GPT's current exposed knobs:

- backend availability
- batch and ubatch tuning
- cache type tuning
- thread and GPU-layer sweeps
- GGUF quantization practices

## Source Register

| Source | URL | Why it matters |
|------|-----|----------------|
| llama.cpp README | https://github.com/ggml-org/llama.cpp/blob/master/README.md | Official capability surface: supported backends, quantization support, hybrid CPU+GPU inference. |
| llama.cpp `llama-bench` README | https://github.com/ggml-org/llama.cpp/blob/master/tools/llama-bench/README.md | Official benchmark surface for `n_batch`, `n_ubatch`, `threads`, `cache-type-k/v`, and `n_gpu_layers`. |
| llama.cpp `quantize` README | https://github.com/ggml-org/llama.cpp/blob/master/tools/quantize/README.md | Official guidance for GGUF quantization workflows, including `--imatrix`. |
| llama.cpp speculative example README | https://github.com/ggml-org/llama.cpp/blob/master/examples/speculative/README.md | Confirms speculative decoding is a first-class upstream topic, though the runtime guidance itself is minimal. |

## Findings

### 1. Upstream runtime capability already covers Pocket GPT's main optimization axes

The official `llama.cpp` README lists:

- integer quantization from 1.5-bit through 8-bit
- CPU+GPU hybrid inference
- OpenCL for Adreno GPU
- Hexagon for Snapdragon, marked as in progress

This means Pocket GPT's current focus on OpenCL, Hexagon, quantized GGUF models, and partial offload is aligned with upstream direction rather than being a custom fork-only idea.

### 2. Official benchmark tooling treats prompt and generation tuning as separate problems

`llama-bench` exposes:

- `--batch-size`
- `--ubatch-size`
- `--cache-type-k`
- `--cache-type-v`
- `--threads`
- `--n-gpu-layers`
- prompt-processing vs text-generation modes (`pp` and `tg`)

The included examples show:

- prompt processing throughput improves materially across batch-size sweeps
- thread scaling differs between prompt processing and token generation
- GPU-layer sweeps are expected as a normal tuning dimension

Implication for Pocket GPT:

- `nBatch`, `nUbatch`, `threads`, and `gpuLayers` should be benchmarked per model tier and backend
- one static setting is unlikely to be globally optimal across prefill and decode

### 3. Quantization quality should be treated as a product input, not a one-time conversion step

The official `quantize` README:

- uses `Q4_K_M` as a canonical example
- calls `--imatrix` "highly recommended"
- supports selective tensor quantization overrides

Implication for Pocket GPT:

- if the project curates its own GGUF artifacts, it should treat importance-matrix-backed quantization as the default curation path
- model catalog entries should be treated as performance-quality assets, not merely downloadable files

### 4. Upstream docs do not provide much direct speculative-parameter guidance

The speculative example README confirms speculative decoding is an active upstream area, but it does not contain the sort of operational guidance needed for Pocket GPT's runtime defaults.

Implication for Pocket GPT:

- speculative-default tuning should be driven primarily by papers plus device benchmarks, not by upstream README text

## Immediate Recommendations For Pocket GPT

1. Mirror `llama-bench` dimensions in Pocket GPT's own benchmark plan:
   - prompt throughput
   - generation throughput
   - `nBatch`
   - `nUbatch`
   - `threads`
   - `gpuLayers`
   - `cache-type-k`
   - `cache-type-v`
2. Keep model quantization policy explicit in the model catalog.
3. Treat batch, ubatch, and thread defaults as benchmark outputs, not hand-picked constants.
