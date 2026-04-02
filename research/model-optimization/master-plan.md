# Pocket GPT Optimization Master Plan

Status: Research-phase synthesis completed on 2026-03-27. Implementation waves 1-6 are now landed in-repo. Remaining work is tracked in `execution/future-work-plan.md` and is now primarily device qualification, evidence gathering, artifact policy, and rollout work.

## 1. Mission

Pocket GPT should ship a local inference stack that is:

- correct
- measurable
- competitive with relevant peers
- configurable through principled policy rather than hacks
- stable across supported Android device classes

## 2. What We Need To Learn

### Internal Inventory

Confirmed from the first code pass:

- native GPU/backend selection, GPU probing, and backend diagnostics
- runtime profiles with threads, batch, ubatch, context, sampling, speculative, mmap, and keep-alive controls
- memory estimation, pre-flight load blocking, and runtime residency policy
- tuning persistence for a subset of runtime parameters
- model routing through static descriptor metadata and device heuristics
- benchmark harnesses and runtime evidence scripts already exist in the repo
- native runtime also already includes prefix cache, disk-backed session cache, context shift/rebuild, and draft-model speculative decoding
- the highest-confidence correctness risks are filename-based quantization heuristics, weak session-cache compatibility metadata, and backend-specific hard-coded guards that are only partially validated

### Preliminary Senior-Lead Observations

From the initial code survey, Pocket GPT already appears to expose a broad optimization surface:

- native GPU backend selection across `AUTO`, `HEXAGON`, `OPENCL`, and CPU
- load-time and generation-time thread, batch, micro-batch, context, and mmap controls
- flash-attention and quantized-KV configuration
- speculative decoding with a separate draft model and adaptive draft-length logic
- prefix-cache reuse and runtime backend diagnostics
- Kotlin-side runtime profiles, GPU qualification, tuning history, and memory estimation

Likely risk clusters from the first pass:

- several defaults are hard-coded globally and may not be device- or model-specific enough
- native safety behavior appears backend-conditional, which suggests some features are present but partially disabled in practice
- the tuning loop appears heuristic-driven rather than benchmark-search-driven
- GPU qualification and tuning promotion thresholds look conservative but static
- memory estimation and runtime profiles appear intentionally simplified and may lag actual runtime behavior

These observations are starting hypotheses only. The team outputs must confirm or overturn them with direct evidence.

### External Inputs

Confirmed from the initial external-research pass:

- speculative decoding is real and valuable, but pairing policy matters
- mature local runtimes expose context, batching, flash attention, KV placement, GPU offload, TTL, and estimate-only workflows explicitly
- KV-cache memory is an important optimization lane, not an afterthought
- Snapdragon backend work should be benchmarked per backend path rather than treated as one generic accelerated mode
- `F16` vs `Q8_0` is the right first product-grade KV-cache validation track; lower-bit KV should remain research-only for now
- OpenCL capability should be probe-driven rather than inferred from version strings alone

## 3. Optimization Backlog

Note: the table below captures the pre-implementation backlog that drove waves 1-6. Shipped status and the still-open post-wave work are maintained under `execution/`.

| Area | Current state | Issue or opportunity | Proposed action | Validation method | Owner |
|------|---------------|----------------------|-----------------|------------------|-------|
| Quantization compatibility | Backend/quant safety partly inferred from filename and model-id heuristics | Brittle and likely to drift as artifacts change | Make GGUF or runtime metadata the source of truth for quantization family and backend compatibility | Model-load matrix across model IDs, quant tiers, OpenCL, Hexagon, and CPU fallback | Native Runtime + Runtime Policy |
| Session-cache safety | Session cache stores prompt/state but not strong compatibility metadata | Concrete correctness risk across model/version/backend/config changes | Extend session-cache header with model/version/path hash, quant, context, backend/profile, and template/tokenizer compatibility markers | Save/load compatibility tests plus mismatched-config rejection tests | Native Runtime |
| Backend qualification model | Backend detection and guards exist, but qualification is coarse | Need separate states for compiled, discovered, qualified, and feature-qualified | Create canonical backend capability and qualification records; gate flash attention separately from basic backend enablement | Real-device qualification matrix for `AUTO`, explicit `OPENCL`, explicit `HEXAGON`, CPU fallback | Native Runtime + Benchmarking |
| Runtime policy tables | Profiles, planner, and tuner exist | Static heuristics dominate and effective runtime limits are partly hidden | Replace scattered heuristics with explicit device/model/backend policy tables | Unit tests plus replayable device benchmarks | Runtime Policy |
| Speculative decoding policy | Runtime mechanism exists and is adaptive | Compatibility and enablement logic are too weak for production policy | Introduce explicit draft-model compatibility matrix and evidence-backed enablement policy | A/B/C runs: speculation off, fixed low lookahead, adaptive lookahead with acceptance-rate telemetry | Native Runtime + Runtime Policy + Benchmarking |
| GPU-layer planning | Default layer targets and retry ladder are coarse | Wasted load attempts and uneven backend behavior | Start from estimate-informed or qualified ceilings instead of static first attempts | Load success rate, first-token latency, decode TPS, and RSS across layer sweeps | Native Runtime + Runtime Policy |
| Benchmark hardening | Stage-2 pipeline exists but can be weakened by token overrides and is not a default optimization gate | Optimization work lacks strong proof obligations | Add `stage2 quick`, raw per-run output, minimum token floors, and risk-triggered benchmark requirements | Historical artifact replay plus locked-default closure reruns | Benchmarking and Validation |
| Routing evidence loop | Auto-routing uses static ranks and heuristics | Best model choice may drift as artifacts and devices change | Feed benchmark evidence back into routing decisions or explicitly freeze routing policy by release | Same-device model comparisons and PocketPal parity spot checks | Runtime Policy + Comparative Systems |
| Artifact curation | Model catalog exists, but quantization policy is not yet a first-class optimization asset | Runtime work can be undermined by weak artifacts | Define GGUF curation and quantization policy, including importance-matrix preference where applicable | Artifact quality review plus benchmark baselines per catalog entry | Comparative Systems + External Research |

## 4. Implementation Streams

### Stream A: Native Hardening

Owner: Native Runtime Lead

- replace filename-based quant heuristics
- harden session-cache compatibility
- formalize backend qualification states
- improve backend, cache, and speculative telemetry

### Stream B: Policy And Tuning Control Plane

Owner: Runtime Policy Lead

- normalize profile defaults versus effective runtime clamps
- create device/model/backend policy tables
- strengthen speculative compatibility policy
- expand or intentionally freeze the tuning surface

### Stream C: Evidence System

Owner: Benchmarking and Validation Lead

- harden stage-2 artifact credibility
- add optimization-risk benchmark triggers
- formalize the tuning device matrix
- store raw per-run data and config fingerprints

### Stream D: Artifact And Comparator Strategy

Owner: Comparative Systems Lead, supported by External Research Lead

- curate transferable patterns from PocketPal, Ollama, LM Studio, and MLX
- define artifact quality and quantization policy
- use comparator parity as a spot-check tool, not a design substitute

## 5. Rollout Phases

### Phase 0: Research And Baseline

- inventory every existing optimization and exposed parameter
- confirm actual behavior against intent
- capture baseline benchmarks on representative devices
- freeze current model artifacts and GGUF metadata expectations for the first tuning cycle

### Phase 1: Correctness Repairs

- replace filename heuristics with metadata-driven compatibility decisions
- add session-cache compatibility metadata and rejection logic
- replace broad speculative profile defaults with a verified compatibility matrix
- align profile defaults, planner clamps, and native effective values for `nBatch` and `nUbatch`
- make backend-specific error reasons usable by the tuning loop
- fix broken or misleading optimizations
- remove or gate hacky behavior that cannot be defended
- tighten diagnostics around backend and tuning choices

### Phase 2: Safe Performance Wins

- add `stage2 quick`, raw per-run outputs, and locked closure floors
- broaden memory rescue logic to try cheaper parameter reductions before hard load blocking
- expand the tuning key to include model version, quantization, context bucket, and backend identity
- benchmark `F16` vs `Q8_0` KV-cache and backend-specific GPU-layer ceilings
- tune parameters with benchmark evidence
- improve backend selection and caching policy
- align model defaults with device-class constraints

### Phase 3: Advanced Features

- evaluate whether lower-bit KV-cache strategies or more advanced speculative policies are worth upstream-compatible investment
- decide whether Hexagon remains experimental or becomes a qualified backend tier
- revisit cache-slot count and cache-state budgets with real usage evidence
- add missing high-value optimizations
- introduce guarded experiments for features with device sensitivity
- expand model-specific tuning and validation coverage

### Phase 4: Productionization

- freeze rollout gates
- document supported device and model matrix
- land regression tests and benchmark checks

## 6. Validation Gates

Every optimization entering implementation must define:

- target devices
- target models and quantizations
- target backend path
- first-token latency target
- decode throughput target
- memory target
- stability target
- fallback behavior on unsupported devices

Optimization-sensitive changes should additionally provide:

- applied config fingerprint
- raw per-run benchmark rows
- negative-control evidence where relevant
- proof that fallback behavior remains correct
- diagnostics sufficient to explain wins and regressions

## 7. Risks To Control

- tuning one device class at the expense of others
- exposing unsupported GPU paths behind weak probes
- letting static profile folklore override measured device evidence
- sharing tuning recommendations across materially different model versions or context envelopes
- introducing benchmark wins that degrade real UX
- relying on comparator behavior without matching Pocket GPT constraints
- adding optimizations without observability or rollback

## 8. Open Questions

1. Which Android device classes are truly in scope for OpenCL acceleration, Hexagon acceleration, or CPU-only fallback?
2. What should be the canonical draft-model pairing map for speculative decoding across the supported Pocket GPT model set?
3. Which parameters should enter the automatic tuning loop first, and which should remain fixed until evidence improves?
4. How should routing policy encode empirical results without becoming unstable or overly device-specific?
5. What is the minimum safe compatibility envelope for restoring a persisted session cache?

## 9. Immediate Priorities After The Initial Pass

1. Runtime policy:
   - replace the current speculative defaulting approach
   - normalize batch and ubatch behavior across profile, planner, and runtime
   - expand the tuning loop beyond the current narrow parameter set
2. Native runtime:
   - verify which optimizations are actually active on-device per backend
   - confirm that diagnostics expose enough detail to explain performance outcomes
3. Benchmarking:
   - define a small but representative device and model matrix
   - separate prompt processing from generation throughput in reporting
4. External and comparator research:
   - use peer products and official docs to set validation order, not to copy blindly
