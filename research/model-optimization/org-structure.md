# Team Structure For The Optimization Program

## Desired Output Shape

Conway's Law applies here: if we organize the team loosely, we will get a patchwork of ad hoc optimizations. The target output is a coherent optimization stack with clear boundaries:

- native runtime correctness and performance
- policy and parameter selection above the runtime
- comparator intelligence from adjacent projects
- external best-practice grounding
- benchmark and rollout proof

## Team Formation Options Considered

### Option A: By Repository

- Pocket GPT internal audit
- PocketPal audit
- Ollama audit
- LM Studio audit
- External research

Pros:
- easy to assign quickly

Cons:
- encourages repo-by-repo summaries instead of a unified optimization architecture
- duplicates effort across runtime, policy, and benchmarking concerns

### Option B: By Optimization Class

- quantization
- GPU/offload
- caching/speculative decoding
- routing/tuning
- benchmarking

Pros:
- maps well to technical topics

Cons:
- cuts across code ownership awkwardly
- risks repeated rediscovery of the same Pocket GPT code paths

### Option C: By Lifecycle Stage

- model acquisition and install
- model load and residency
- prompt prefill and decode
- streaming/tooling/UI integration
- validation

Pros:
- maps well to user-visible flow

Cons:
- too broad per team for the research phase
- comparator analysis becomes fragmented

### Chosen Option: Architecture + Evidence Hybrid

We will use five team leads:

1. Native Runtime Lead
2. Runtime Policy Lead
3. Comparative Systems Lead
4. External Research Lead
5. Benchmarking and Validation Lead

Why this shape:

- mirrors the actual Pocket GPT architecture
- keeps native and policy concerns separate
- prevents comparator work from distorting the target architecture
- forces every recommendation to connect back to measurable validation

## Org Chart

- Senior Lead
  - owns program direction, standards, integration, and final rollout plan
- Team Leads
  - own one subsystem boundary each
  - must spawn engineer subagents for bounded tasks
  - must synthesize engineer outputs into a lead summary
- Engineers
  - own disjoint investigations or documents
  - do concrete file-based research work

## Lead Charters

### 1. Native Runtime Lead

Scope:

- `apps/mobile-android/src/main/cpp/pocket_llama.cpp`
- JNI/native bridge behavior
- load/generation parameters
- backend setup and fallback behavior
- GPU offload, flash attention, KV cache, speculative decode, prefix cache

Expected outputs:

- `findings/native-runtime/lead-summary.md`
- engineer notes for runtime parameters and backend behavior

Recommended engineer pods:

- JNI/runtime parameter audit
- GPU/backend capability audit
- speculative/cache path audit

### 2. Runtime Policy Lead

Scope:

- `packages/app-runtime/`
- `packages/inference-adapters/`
- Android runtime tuning and model memory estimation
- routing policy, performance profiles, config mapping, residency decisions

Expected outputs:

- `findings/runtime-policy/lead-summary.md`
- engineer notes for routing/tuning and memory estimation

Recommended engineer pods:

- routing and config mapping audit
- tuning/recommendation store audit
- memory/residency estimation audit

### 3. Comparative Systems Lead

Scope:

- `pocketpal-ai/`
- `ollama/`
- `lms/`
- `lmstudio-js/`
- `mlx-engine/`

Expected outputs:

- `findings/comparative-systems/lead-summary.md`
- repo-specific comparator notes

Recommended engineer pods:

- PocketPal mobile comparator
- Ollama runtime comparator
- LM Studio plus MLX comparator

### 4. External Research Lead

Scope:

- official docs for `llama.cpp`, `ggml`, Android GPU stacks, and related libraries
- research papers and high-signal technical writeups relevant to mobile inference
- tuning guidance for speculative decoding, KV cache, quantization, batching, and backend selection

Expected outputs:

- `findings/external-research/lead-summary.md`
- source-backed topic notes

Recommended engineer pods:

- runtime/library best practices
- mobile GPU/backend best practices
- papers and parameter-tuning research

### 5. Benchmarking And Validation Lead

Scope:

- current Pocket GPT benchmark harnesses and evidence flow
- validation criteria for correctness, performance, stability, and device compatibility
- parameter sweep methodology and rollout gates

Expected outputs:

- `findings/benchmarking-validation/lead-summary.md`
- benchmark and rollout design notes

Recommended engineer pods:

- current harness audit
- device matrix and experiment design
- observability and acceptance gates

## Team Interaction Protocol

1. Leads must assign disjoint files to engineer pods.
2. Leads synthesize, not just forward raw notes.
3. If two leads touch the same optimization, ownership is:
   - Native Runtime for mechanism correctness
   - Runtime Policy for selection/tuning logic
   - Benchmarking for proof and acceptance gates
4. Comparator findings are inputs, not requirements.
5. External research informs the implementation plan only after it is mapped to Pocket GPT code paths and validation steps.

## Senior Lead Responsibilities

- audit the completeness of all team outputs
- challenge weak evidence or hand-wavy recommendations
- consolidate findings into the master implementation roadmap
- ensure no optimization enters the plan without:
  - a code owner
  - a measurement method
  - a rollback story
