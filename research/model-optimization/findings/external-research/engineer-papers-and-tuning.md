# External Research Engineer: Papers And Tuning

Date accessed: 2026-03-31

## Scope

Primary papers and official proceedings most relevant to speculative decoding and KV-cache optimization decisions for Pocket GPT.

## Source Register

| Source | URL | Why it matters |
|------|-----|----------------|
| Fast Inference from Transformers via Speculative Decoding | https://proceedings.mlr.press/v202/leviathan23a.html | Foundational speculative decoding paper with exact-output guarantee and 2x-3x reported acceleration on T5-XXL. |
| Accelerating Large Language Model Decoding with Speculative Sampling | https://arxiv.org/abs/2302.01318 | Early draft-model speculative sampling paper with exact-distribution preservation and 2-2.5x reported speedups on Chinchilla. |
| Dynamic Speculation Lookahead Accelerates Speculative Decoding of Large Language Models | https://proceedings.mlr.press/v262/mamou24a.html | Direct evidence that static speculation lookahead is suboptimal and dynamic lookahead helps. |
| KIVI: A Tuning-Free Asymmetric 2bit Quantization for KV Cache | https://arxiv.org/abs/2402.02750 | Strong evidence that KV-cache quantization can matter a lot, but also that good results require asymmetric, structure-aware treatment of K and V. |
| Medusa: Simple LLM Inference Acceleration Framework with Multiple Decoding Heads | https://arxiv.org/abs/2401.10774 | Useful future-facing reference for speculative-style speedups that require model modification rather than an external draft model. |

## Findings

### 1. Speculative decoding is a strong fit for Pocket GPT's current architecture

Leviathan et al. show speculative decoding can accelerate inference:

- without changing model outputs
- without retraining
- by verifying several draft tokens in parallel

Chen et al. make the same core case with speculative sampling:

- multiple tokens per target-model call
- exact distribution preserved through modified rejection sampling
- 2x-2.5x reported speedup in their setup

Implication for Pocket GPT:

- Pocket GPT's current draft-model approach is directionally sound
- the main question is not "should speculative decoding exist?" but "when does it help on-device enough to justify the extra model and memory cost?"

### 2. Static draft length is probably leaving performance on the table

Mamou et al. show that a fixed speculation lookahead is suboptimal and that dynamic selection of speculation lookahead improves over the best static baseline.

Implication for Pocket GPT:

- Pocket GPT's current acceptance-rate-based adaptive draft sizing is aligned with current research direction
- it should be benchmarked carefully rather than replaced with a fixed draft length

### 3. KV-cache quantization is promising, but low-bit KV is not a drop-in default

KIVI argues:

- keys and values should not be quantized identically
- per-channel treatment for K and per-token treatment for V matters
- specialized 2-bit KV approaches can reduce peak memory substantially and improve throughput

Implication for Pocket GPT:

- aggressive KV-cache compression deserves research attention
- but simply turning on lower-bit KV types in a generic runtime is not equivalent to implementing KIVI-style logic
- `Q8_0` is a practical baseline; lower-bit KV should remain experimental until quality, backend support, and runtime overhead are validated

### 4. Medusa-style acceleration is interesting but is not an immediate fit

Medusa reports strong speedups, but it does so by augmenting the model with extra decoding heads and fine-tuning procedures.

Implication for Pocket GPT:

- Medusa is a roadmap candidate, not a near-term optimization
- Pocket GPT can pursue stronger wins sooner through runtime-level speculative tuning before considering model-modification approaches

## Parameter And Tuning Guidance For Pocket GPT

### Speculative decoding

- Validate speculative decoding per model family, not globally.
- Start by comparing:
  - speculative disabled
  - static low lookahead
  - adaptive lookahead
- Keep the draft model in the same tokenizer/model family when possible.
- Measure at least:
  - acceptance rate
  - added memory pressure
  - prefill cost of the draft model
  - total decode throughput

### KV-cache quantization

- Validate `F16` vs `Q8_0` first.
- Treat `Q4_*` KV as research, not product default, unless quality and backend stability are proven.
- Track long-context and multi-turn behavior separately from short single-turn benchmarks.

### Future acceleration candidates

- Medusa-style multi-head decoding
- more advanced speculative variants once Pocket GPT has stable device benchmarking and acceptance-rate telemetry
