# Comparative Systems Lead Summary

Status: Completed initial research pass.

## Scope

- `pocketpal-ai/`
- `ollama/`
- `lms/`
- `lmstudio-js/`
- `mlx-engine/`

## High-Value Patterns Pocket GPT Should Copy

- Treat model lifecycle as a first-class system concern.
  - PocketPal shows a mobile-friendly offload/reload pattern tied to app background and foreground transitions.
  - LM Studio exposes lifecycle controls such as TTL, progress callbacks, and explicit load config access.
- Separate load-time tuning from prediction-time tuning.
  - LM Studio’s typed `LLMLoadModelConfig` versus `LLMPredictionConfig` is materially cleaner than mixing everything into one opaque blob.
  - Pocket GPT should adopt this idea internally even if the UI remains simplified.
- Normalize and gate risky runtime combinations before dispatch.
  - Ollama caps context to training context, clamps batch to context, derives KV size from context and parallelism, and rejects unsupported flash-attention or KV-cache combinations.
  - PocketPal clamps batch and uBatch against context before runtime invocation.
- Improve cache strategy and observability together.
  - Ollama has explicit cache-slot policies, slot reuse, slot forking, and recurrent checkpoint restore.
  - MLX trims prompt caches to the common prefix and quantizes KV during prefill.
  - LM Studio returns load config, prediction config, and runtime stats with the prediction result.
- Capture benchmark evidence as structured data, not screenshots plus prose.
  - PocketPal stores benchmark config and init settings with results.
  - Ollama’s bench tool splits `load`, `prefill`, and `generate` into distinct measurable stages.

## Patterns Pocket GPT Should Avoid

- Avoid copying server-first concurrency patterns directly into a mobile app.
  - Ollama’s multi-user cache policies and broader runner complexity solve real server problems, but they are not a default fit for Pocket GPT.
- Avoid pushing every low-level runtime flag into the user-facing UI.
  - PocketPal exposes many knobs that are useful for experimentation but expensive in support and consistency.
- Avoid backend heuristics that only live in one layer.
  - PocketPal’s explicit device gating is helpful, but Pocket GPT should prefer capability checks that are also enforced deeper in the runtime.
- Avoid tying Pocket GPT’s design to MLX-specific mechanics.
  - MLX is valuable as a source of patterns, not as a portability target.

## Constraints That Make Some Comparator Ideas Non-Transferable

- PocketPal uses `llama.rn` and a React Native architecture, so its exact setting names and lifecycle hooks do not map directly to Pocket GPT’s JNI/Kotlin stack.
- Ollama assumes a desktop/server environment with runner processes, larger memory budgets, and multi-user concurrency.
- LM Studio and `mlx-engine` assume desktop-class model management and, for MLX, Apple-specific acceleration.
- `lms` contributes more operational ergonomics than runtime optimization ideas.

## Top-Priority Comparator Insights

1. Pocket GPT needs a stricter configuration contract.
   - Copy the LM Studio split between load config and prediction config.
   - Copy Ollama’s validation approach for context, batch, flash attention, and KV cache combinations.
2. Pocket GPT should treat cache strategy as a design space, not a single implementation detail.
   - Ollama shows that prefix reuse policy, slot reuse, and checkpoint restore are independent choices.
   - MLX shows that prompt-cache trimming and draft-model cache management can be made explicit.
3. Pocket GPT should improve research and implementation observability together.
   - Benchmark runs should record applied settings, not just token speeds.
   - Runtime results should preserve applied config and backend stats so tuning work is auditable.
4. Pocket GPT should copy PocketPal’s mobile-specific lifecycle strengths, not its full settings surface.
   - Auto offload/load and GGUF metadata integration are directly relevant.
   - The very broad user-facing tuning panel is not.

## Engineer Notes

- `engineer-pocketpal.md`
- `engineer-ollama.md`
- `engineer-lmstudio-mlx.md`
