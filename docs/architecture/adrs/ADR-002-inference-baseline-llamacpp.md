# ADR-002: Inference Baseline via llama.cpp + GGUF

## Status

Accepted

## Context

Phase 0 needs an Android local inference baseline that can be integrated quickly to validate latency, memory, and thermal feasibility on real devices.

## Decision

Adopt `llama.cpp` with GGUF quantized model artifacts as the baseline runtime for Phase 0 and MVP.

## Consequences

Positive:

1. Fastest path to practical local inference on Android
2. Strong ecosystem support for quantized open models
3. Reduces dependency on high-risk conversion pipelines early

Negative:

1. Platform-specific acceleration may still outperform baseline
2. Multimodal integration can require extra engineering

Mitigation:

1. Keep adapter interface clean for runtime replacement
2. Run parallel optimization tracks post-baseline
