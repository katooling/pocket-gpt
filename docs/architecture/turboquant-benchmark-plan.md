# TurboQuant Benchmark Plan

Validation plan for promoting custom TQ_Q3_LM / TQ_Q2_LM types from
experimental to production defaults for ULTRA and EXTREME presets.

---

## 1. Perplexity Benchmarks

**Goal**: Measure quality impact of each KV cache quantization preset on real
models, establishing the empirical bpw/quality curve for our implementation.

### Setup

| Model class     | Example                   | Size   | head_dim |
|-----------------|---------------------------|--------|----------|
| Small           | SmolLM2-360M-Instruct     | ~720MB | 64       |
| Medium          | Phi-3.5-mini (Q4_K_M)     | ~2.3GB | 96       |
| Large           | Llama-3.1-8B (Q4_K_M)     | ~4.6GB | 128      |

### Metrics

- **Perplexity (PPL)**: Measured on WikiText-2 test set, 2048-token context.
  Report mean and variance over 3 runs.
- **Delta PPL** = PPL(preset) - PPL(F16 KV baseline). The key metric.

### Test Matrix

For each model, measure PPL with:

| Preset   | Standard V type | Experimental V type | Expected delta PPL |
|----------|-----------------|---------------------|--------------------|
| SAFE     | F16             | F16                 | 0 (baseline)       |
| BALANCED | Q8_0            | Q8_0                | < 0.05             |
| AGGRESSIVE| Q4_0           | Q4_0                | < 0.2              |
| ULTRA    | Q3_K            | TQ_Q3_LM            | < 0.5              |
| EXTREME  | Q2_K            | TQ_Q2_LM            | < 1.0              |

### Pass/Fail Criteria

- Custom types must produce delta PPL within 10% of the standard ggml
  type delta PPL for the same preset.
- Absolute delta PPL for ULTRA must be < 0.5 on the large model.
- Absolute delta PPL for EXTREME must be < 1.0 on the large model.

---

## 2. Latency Benchmarks

**Goal**: Ensure rotation hooks and custom quantization do not regress
inference latency beyond acceptable thresholds on production hardware.

### Metrics

- **Token generation speed** (tokens/sec): Measure decode throughput during
  a 512-token generation with 1024-token prompt.
- **Time-to-first-token (TTFT)**: Prompt evaluation latency for a
  1024-token prompt.
- **Rotation hook overhead**: Isolated measurement of WHT forward/inverse
  per token (instrument with Android Trace or manual timing).

### Target Devices

| Device class | Example          | SoC          |
|--------------|------------------|--------------|
| Flagship     | Pixel 9 Pro      | Tensor G4    |
| Mid-range    | Pixel 7a         | Tensor G2    |
| Budget       | Pixel 6a         | Tensor (G1)  |

### Test Matrix

For each device and model (Phi-3.5-mini Q4_K_M recommended as standard):

| Configuration              | Expected overhead |
|----------------------------|-------------------|
| SAFE (F16, no rotation)    | baseline          |
| BALANCED (Q8_0 + rotation) | < 5% decode, < 3% TTFT |
| ULTRA (Q3_K + rotation)    | < 8% decode, < 5% TTFT |
| ULTRA experimental (TQ_Q3_LM + rotation) | < 10% decode, < 7% TTFT |
| EXTREME (Q2_K + rotation)  | < 8% decode, < 5% TTFT |
| EXTREME experimental (TQ_Q2_LM + rotation) | < 10% decode, < 7% TTFT |

### Pass/Fail Criteria

- No preset should regress decode throughput by more than 15% vs F16 baseline.
- TTFT regression must be < 10% for all presets.
- Custom types must not be more than 5% slower than the corresponding
  standard ggml type preset.

---

## 3. Memory Benchmarks

**Goal**: Verify that actual runtime memory usage matches the Kotlin memory
estimator predictions and that memory savings are real.

### Metrics

- **Peak RSS** during inference (via /proc/self/status or Android profiler).
- **KV cache allocation size** from llama.cpp telemetry.
- **Estimator accuracy** = |predicted - actual| / actual.

### Pass/Fail Criteria

- Estimator accuracy must be within 15% for all presets.
- ULTRA/EXTREME must show measurable KV cache memory reduction vs AGGRESSIVE.

---

## 4. Stability Benchmarks

**Goal**: Verify model loading reliability across the stability matrix.

### Test Matrix

| Dimension            | Values                              |
|----------------------|-------------------------------------|
| Model size           | < 2GB (small clamp), >= 2GB         |
| head_dim             | 64 (power-of-2), 80 (non-power)    |
| Preset               | SAFE, BALANCED, AGGRESSIVE, ULTRA, EXTREME |
| Flash attention      | on, off (v_trans path)              |
| Experimental types   | on, off                             |
| Failpoints           | session alloc fail, hook reg fail, rotation unsupported |

### Pass/Fail Criteria

- 100% model load success for valid configurations.
- All failpoint paths result in clean F16 fallback or clean failure.
- No partially-valid TurboQuant state after any failure path.
- Diagnostics JSON always reflects the actual effective KV mode.

---

## 5. Execution Order

1. **Stability benchmarks first** — gate on 100% reliability before
   measuring quality/performance.
2. **Perplexity benchmarks** — establish quality baselines and deltas.
3. **Latency benchmarks** — ensure performance is acceptable.
4. **Memory benchmarks** — confirm savings match estimates.

Only after all four gates pass should custom types be promoted from
experimental (feature-gated) to production defaults.

---

*Last updated: 2026-03-30*
