# TurboQuant Research Learnings

Sources of truth for ongoing TurboQuant KV cache compression development.
All findings are tagged with their evidence source.

---

## From the TurboQuant Paper (arXiv 2504.19874)

- **Two-stage quantization is essential for unbiased inner products at low bit.**
  Stage 1 is a (b-1)-bit MSE-optimal scalar quantizer; stage 2 is a 1-bit QJL
  transform on the residual. Without QJL, low-bit quantization introduces
  systematic inner-product bias. *[Paper §3.2, Theorem 2]*

- **Rotation must be applied to Q, K, and V consistently.**
  Q rotated forward, K/V stored in the rotated domain, attention output
  inverse-rotated before W_o. Missing any leg breaks attention correctness.
  *[Paper §3.1, Algorithm 1]*

- **3.5 bpw is the quality-neutral threshold; 2.5 bpw shows marginal degradation.**
  These numbers assume the full paper algorithm (rotation + Lloyd-Max + QJL).
  *[Paper §4, Table 1]*

- **The paper assumes Haar-random rotation.**
  SRHT (Signed Randomized Hadamard Transform) is a practical O(d log d)
  approximation. The Beta(d/2, d/2) coordinate distribution from Lemma 1 is
  not exact under SRHT but is close enough for d >= 64. There is no published
  comparative benchmark of SRHT vs Haar-random for this specific application.
  *[Paper §3.1, Lemma 1; practical assessment from our test suite]*

## From KIVI (arXiv 2402.02750)

- **Asymmetric K/V precision is the key actionable insight.**
  Keys drive attention weights and errors compound multiplicatively across all
  queries. Values affect output linearly through the softmax-weighted sum.
  *[KIVI §3.1]*

- **Per-channel key quantization is motivated by heterogeneous channel variance.**
  After WHT rotation, channel variance becomes more homogeneous, reducing but
  not necessarily eliminating the per-channel benefit. Our implementation uses
  blockwise (groups of 32) quantization instead, which is a practical trade-off.
  *[KIVI §3.2; engineering assessment, not a paper-backed theorem]*

## From Implementation Experience

- **ggml Q_K types (Q2_K, Q3_K) are blockwise non-uniform quantizers.**
  They include per-block scales and mins, not simple uniform levels. Using them
  with WHT rotation is a reasonable engineering approximation even without custom
  Lloyd-Max codebooks. *[Verified in ggml source: ggml-quants.c]*

- **WHT stack buffer (TQ_STACK_MAX = 1024).**
  The live rotation callback uses stack-allocated scratch for head_dim <= 1024,
  avoiding hot-path malloc. Batch helpers in test code use session scratch or
  fallback malloc. *[turboquant.c, pocket_llama.cpp]*

- **SRHT practical quality.**
  Our test suite shows kurtosis drops from > 50 to < 5, inner-product distortion
  is < 0.023 for Q8_0 and < 0.10 for Q4_0. These numbers are specific to our
  SRHT implementation, not theoretical Haar-random bounds.
  *[test_turboquant.cpp: test_rotation_gaussianity, test_inner_product_preservation]*

- **Context re-creation on hook failure is safe.**
  llama.cpp supports destroying and recreating a context from the same model
  handle. This is how the F16 recovery path works when rotation hook registration
  fails. *[Verified in pocket_llama.cpp runtime]*

- **Protected layers (first/last 2) are important.**
  Embedding and output projection layers are most sensitive to quantization error.
  Skipping rotation for these layers preserves quality at minimal memory cost.
  *[Standard practice in quantization literature; KIVI §4.2]*

- **Small models (< 2 GB) need preset clamping.**
  ULTRA is demoted to BALANCED K / BALANCED V, EXTREME is demoted to
  BALANCED K / AGGRESSIVE V. This prevents aggressive quantization on models
  whose KV cache is already small. *[pocket_llama.cpp: resolve_turboquant_kv_types]*

## What We Do Not Yet Know

- **Actual perplexity impact** of our ggml-backed ULTRA/EXTREME presets on
  real models (synthetic tests only).

- **Whether SRHT gives meaningfully worse results than Haar-random** for our
  specific use case (no comparative benchmark exists in our codebase).

- **Runtime latency of the rotation hooks** on production Android devices
  (not benchmarked).

- **QJL decode cost feasibility** at inference time — current O(d²) naive
  implementation is likely too slow without NEON vectorization or a structured
  projection matrix.

- **Custom low-bit types in ggml interaction with GPU backends.** Our custom
  `GGML_TYPE_TQ_Q3_LM` / `GGML_TYPE_TQ_Q2_LM` types only have CPU
  quantize/dequantize implementations. They will not be accelerated by OpenCL
  or other GPU backends without explicit support.

---

*Last updated: 2026-03-30*
