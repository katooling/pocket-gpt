# External Research Lead Summary

Status: Verified against current upstream docs and papers on 2026-03-31.

## Scope

- official runtime docs
- library best practices
- relevant research papers
- mobile inference implementation guidance

## Executive Summary

1. Pocket GPT is pursuing the right upstream-aligned optimization surfaces:
   - OpenCL on Adreno
   - Hexagon on Snapdragon
   - quantized GGUF models
   - speculative decoding
   - KV-cache tuning
2. The safest external-research-backed mobile baseline today is:
   - conservative OpenCL compatibility targeting
   - `F16` and `Q8_0` as the initial KV-cache validation set on accelerated mobile paths
   - Hexagon behind qualification
   - speculative decoding tuned through benchmark evidence rather than fixed folklore
3. The highest-value tuning surfaces to validate first are:
   - `nBatch`
   - `nUbatch`
   - `threads`
   - `gpuLayers`
   - speculative lookahead behavior
   - `F16` vs `Q8_0` KV-cache
4. The most important caution areas are:
   - Hexagon flash attention
   - low-bit KV-cache defaults
   - assuming OpenCL capability from version strings alone
   - importing advanced research ideas before Pocket GPT has strong device telemetry

## Concrete Findings

### 1. Upstream `llama.cpp` supports the same optimization territory Pocket GPT is exposing

Official `llama.cpp` docs describe:

- OpenCL targeting Adreno GPUs
- Hexagon targeting Snapdragon, still marked as in progress
- 1.5-bit through 8-bit quantization
- CPU+GPU hybrid inference
- official benchmarking knobs for batch, ubatch, cache types, threads, and GPU layers

Interpretation for Pocket GPT:

- Pocket GPT's runtime surface is not misaligned with upstream direction
- the main risk is poor tuning and insufficient qualification, not choosing the wrong categories of optimizations

### 2. Batch and ubatch should be treated as first-class tuning parameters, not stable constants

Pocket GPT currently carries a default `nBatch` of `512` in both the native runtime and bridge contract, and a default context size of `2048`. Upstream `llama-bench` documentation treats batch size, ubatch size, threads, GPU layers, and cache types as normal benchmark axes, and explicitly separates prompt processing from generation throughput.

Interpretation for Pocket GPT:

- keep the existing defaults as temporary baselines
- do not assume `512` is globally correct for both CPU-only and accelerated paths
- benchmark prompt and generation separately because the external evidence says they respond differently to tuning

### 3. Speculative decoding is justified, but the draft schedule must remain adaptive and benchmarked

The speculative decoding literature shows exact-output acceleration is real with a smaller draft model plus verification by the target model. Dynamic speculation research also shows that fixed speculation lookahead is suboptimal relative to adaptive behavior.

Interpretation for Pocket GPT:

- the current adaptive speculative direction is defensible
- Pocket GPT should validate:
  - speculation disabled
  - fixed low lookahead
  - current adaptive lookahead
- the success metric is not only tokens per second; it also includes:
  - acceptance rate
  - draft-model memory overhead
  - end-to-end latency

### 4. `Q8_0` is the clearest upstream mobile KV-cache baseline, but not a universal default

External evidence points in one direction:

- official Snapdragon examples in upstream `llama.cpp` show `K (q8_0)` and `V (q8_0)` on Hexagon
- KIVI shows that deeper KV compression is promising, but also that strong results rely on asymmetric, structure-aware quantization rather than naive low-bit replacement

Interpretation for Pocket GPT:

- `F16` and `Q8_0` should be the initial product-quality validation set
- lower-bit KV types should stay experimental until validated for:
  - quality
  - long-context stability
  - backend compatibility
  - actual net speedup

### 5. Hexagon enablement and Hexagon flash attention are separate decisions

Official Snapdragon backend docs say:

- Hexagon is experimental
- `FLASH_ATTN_EXT` is gated behind `GGML_HEXAGON_EXPERIMENTAL=1`
- session count scales with model size

Interpretation for Pocket GPT:

- "Hexagon works on this device" does not imply "Hexagon flash attention is ready"
- backend qualification should include:
  - backend identity
  - max stable GPU/HTP layers
  - memory budget including repack buffers
  - number of required sessions for the chosen model tier

### 6. OpenCL version labels are not enough for runtime safety

The Khronos OpenCL 3.0 spec makes many OpenCL C 2.0-era features optional and queryable, which means nominal version support is not equivalent to capability parity.

Interpretation for Pocket GPT:

- targeting a conservative OpenCL language level for older Adreno devices is reasonable
- Pocket GPT should still improve runtime diagnostics and feature probing rather than assuming device behavior from version labels alone

## Recommendations Most Relevant To Pocket GPT

### Validate First

1. `F16` vs `Q8_0` KV-cache on:
   - CPU-only
   - OpenCL
   - Hexagon when qualified
2. `nBatch` and `nUbatch` sweeps for each shipping model tier.
3. speculative decoding:
   - off
   - fixed low lookahead
   - current adaptive lookahead
4. prompt-processing and generation as separate benchmark lanes.
5. backend memory reporting that separates:
   - host memory
   - repack memory
   - KV-cache memory
   - compute buffers

### Device-Specific Gating Required

1. Hexagon backend enablement:
   - gate by device qualification and model tier
2. Hexagon flash attention:
   - keep experimental until direct device evidence says otherwise
3. OpenCL optimization assumptions:
   - gate by runtime feature probe, not by version string alone
4. aggressive KV-cache quantization below `Q8_0`:
   - gate by backend and quality validation
5. multi-session large-model execution on Hexagon:
   - gate by memory/session evidence, not by download size alone

### Research-Only Or Future-Candidate Work

1. KIVI-like asymmetric low-bit KV-cache implementations.
2. Medusa-style multi-head decoding, because it changes model requirements rather than only runtime behavior.
3. More aggressive per-tensor or per-layer GGUF quantization policies, after the benchmark harness is stronger.

## Initial Parameter Guidance

These are the first externally-justified validation ranges or pairings to test, not final product defaults:

- KV cache:
  - `F16`
  - `Q8_0`
- speculative draft tokens:
  - current adaptive range should stay in contention
  - compare against fixed low values before changing defaults
- batch and ubatch:
  - benchmark at multiple values instead of preserving `512` by assumption
- GPU layers:
  - benchmark per backend and model tier
  - do not assume the same stable maximum across OpenCL and Hexagon
- context:
  - keep current `2048` as baseline while measuring memory effects before longer-context rollout

## What This Means For The Current Pocket GPT Defaults

The current Pocket GPT surface already contains several externally sensible choices:

- OpenCL compatibility targeting is conservative
- `Q8_0` is the default KV-cache baseline in the bridge contract
- speculative decoding has adaptive behavior rather than being purely static

What is still missing is proof:

- per-device evidence
- per-model evidence
- backend-specific guardrails
- diagnostics detailed enough to explain why a faster or slower outcome happened

## Top-Priority References

1. `llama.cpp` README  
   https://github.com/ggml-org/llama.cpp/blob/master/README.md
2. `llama.cpp` Snapdragon backend README  
   https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md
3. `llama.cpp` `llama-bench` README  
   https://github.com/ggml-org/llama.cpp/blob/master/tools/llama-bench/README.md
4. `llama.cpp` `quantize` README  
   https://github.com/ggml-org/llama.cpp/blob/master/tools/quantize/README.md
5. Khronos OpenCL 3.0 unified specification  
   https://registry.khronos.org/OpenCL/specs/3.0-unified/html/OpenCL_API.html
6. Leviathan et al., *Fast Inference from Transformers via Speculative Decoding*  
   https://proceedings.mlr.press/v202/leviathan23a.html
7. Chen et al., *Accelerating Large Language Model Decoding with Speculative Sampling*  
   https://arxiv.org/abs/2302.01318
8. Mamou et al., *Dynamic Speculation Lookahead Accelerates Speculative Decoding of Large Language Models*  
   https://proceedings.mlr.press/v262/mamou24a.html
9. Liu et al., *KIVI: A Tuning-Free Asymmetric 2bit Quantization for KV Cache*  
   https://arxiv.org/abs/2402.02750

## Open Questions

1. Does the vendored `llama.cpp` revision in Pocket GPT behave identically enough to current upstream for all discussed flags and backends?
2. Does Pocket GPT already log enough telemetry to compare speculative acceptance rate against end-to-end speedup?
3. Can the current benchmark harness attribute failures or regressions to:
   - KV-cache type
   - repack memory pressure
   - backend-specific flash-attention behavior
4. Which model families in Pocket GPT have the best draft-model pairing options with tokenizer compatibility and acceptable memory overhead?

## Blockers

No blocker prevented this initial pass.

What remains missing is device evidence, not source evidence.
