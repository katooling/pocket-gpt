# Engineer Note: Ollama

## Topic

Ollama as the strongest comparator for runtime discipline, cache policy, and benchmark semantics.

## Current State

- Ollama applies tighter validation and normalization at load time than the mobile comparators.
- It has a clearer separation between load-time choices, cache behavior, and benchmark reporting.
- Its runner stack contains more advanced cache infrastructure than Pocket GPT currently exposes.

## Evidence

- `ollama/llm/server.go`
  - Caps requested context length to the model training context when the request exceeds it.
  - Clamps `NumBatch` to `NumCtx`.
  - Computes `KvSize` as `opts.NumCtx * numParallel`.
  - Derives default threads from system info unless explicitly overridden.
  - Builds a `LoadRequest` that includes `BatchSize`, `Parallel`, `KvCacheType`, `FlashAttention`, `MainGPU`, and `MultiUserCache`.
- `ollama/llm/server.go:200-257`
  - Enables flash attention only when both hardware and the model support it.
  - Refuses quantized KV cache types when flash attention is disabled.
  - Checks model support before accepting a requested KV cache type.
- `ollama/runner/llamarunner/runner.go:827-927`
  - Loads models with explicit `NumGpuLayers`, `UseMmap`, `TensorSplit`, and per-request parallelism.
  - Creates context parameters from `kvSize`, batch size, parallelism, threads, flash attention mode, and KV cache type.
- `ollama/runner/llamarunner/cache.go`
  - Has two cache-slot policies:
    - single-user: reuse the longest prefix match
    - multi-user: choose the best slot while balancing reuse and eviction
  - Supports slot forking to reuse prefixes across slots.
  - Performs partial cache erasure and falls back to full reset if partial erase is unsupported.
- `ollama/runner/ollamarunner/cache.go`
  - Generalizes cache handling through `kvcache.Cache`.
  - Restores to checkpoint-safe positions when recurrent caches support checkpoint restore.
- `ollama/kvcache/cache.go`
  - Defines a strong cache interface with `Init`, `StartForward`, `CopyPrefix`, `CanResume`, and `Remove`.
- `ollama/kvcache/recurrent.go` and `ollama/kvcache/recurrent_checkpoints.go`
  - Add hybrid recurrent cache support with checkpoint planning and restore mechanics.
  - Preserve prefix reuse beyond a basic causal cache.
- `ollama/cmd/bench/bench.go`
  - Benchmarks separate `load`, `prefill`, and `generate` steps.
  - Emits normalized metrics such as ns/token and tokens/sec.
  - Supports a `keepAlive` duration so model residency effects can be tested.

## Risk Or Gap

- Ollama is server-first and multi-user aware. Some of that complexity is unnecessary or actively harmful on a mobile, single-user app.
- Its concurrency and multi-device GPU split logic do not transfer directly to Android phones.
- The runner architecture is much heavier than what Pocket GPT should mirror in-process.

## Recommendation

- Pocket GPT should copy:
  - load-time normalization of context, batch, and KV sizing
  - strict gating for flash attention and quantized KV cache combinations
  - explicit benchmark separation between load, prefill, and generate
  - prefix-cache policy design as an intentional strategy instead of a single hard-coded path
- Pocket GPT should avoid:
  - carrying over server-side multi-user complexity by default
  - adopting heavyweight runner/process architecture unless Android isolation demands it

## Open Questions

- Which Ollama cache ideas are still beneficial for a strictly single-user mobile runtime?
- Can Pocket GPT adopt a simplified checkpoint or slot-forking strategy without inheriting the full runner complexity?
