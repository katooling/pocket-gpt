# Future Work Plan

Status: Repo-side correctness and policy hardening from the original optimization program is complete through wave 6. Remaining work now depends on real-device evidence, artifact policy, and release qualification rather than another blind code pass.

## Senior Lead

- Own final acceptance criteria, cross-lead prioritization, and release/no-release decisions.
- Require every remaining item to ship with:
  - target devices
  - target models and quantizations
  - target backend path
  - raw benchmark evidence
  - fallback proof
  - rollback plan

Definition of done:
- A signed release matrix exists for supported device classes, model families, and backend tiers.
- All lead deliverables below are complete and linked from the execution package.

## Native Runtime Lead

Goal: graduate backend support from "implemented" to "qualified."

Sublead A: Backend qualification pod
- Build the device/backend qualification matrix for CPU, OpenCL, and Hexagon.
- Confirm compiled, discovered, qualified, and feature-qualified states on representative devices.
- Verify backend diagnostics emitted by the app match actual runtime behavior.

Sublead B: Feature qualification pod
- Validate flash attention, quantized-KV variants, and GPU-layer ceilings per backend.
- Confirm fallback behavior when a requested backend or feature is not safe.

Definition of done:
- For each supported backend tier, the team can show real-device pass/fail evidence, fallback behavior, and known unsupported combinations.
- Qualification results are captured in a device/model/backend matrix with reproducible commands and logs.

## Runtime Policy Lead

Goal: replace the remaining broad heuristics with evidence-backed policy tables.

Sublead A: Tuning sweep pod
- Sweep `nThreads`, `nThreadsBatch`, `nBatch`, `nUbatch`, `gpuLayers`, speculative mode, and KV cache type by device class, backend, and model family.
- Produce recommended operating envelopes rather than one-off best runs.

Sublead B: Policy table pod
- Convert benchmark results into explicit device/model/backend policy tables.
- Freeze or remove knobs that do not hold up across the matrix.

Sublead C: Routing and artifact-fit pod
- Revisit routing once empirical evidence is available.
- Ensure model defaults align with supported device classes and curated artifact choices.

Definition of done:
- Policy tables exist in code or release config with a source benchmark set behind every nontrivial default.
- Tuning recommendations no longer rely on folklore or one-device wins.

## Benchmarking And Validation Lead

Goal: make optimization claims release-grade rather than anecdotal.

Sublead A: Device-matrix pod
- Define the representative Android device matrix by RAM class, SoC family, and backend capability.
- Maintain the required benchmark lanes per device class.

Sublead B: Benchmark operations pod
- Run `stage2-quick` and full closure runs for every risky optimization or policy-table change.
- Archive raw per-run outputs, config fingerprints, and promotion reports.

Sublead C: Acceptance-gate pod
- Turn the benchmark evidence into promotion rules for model, backend, and release changes.
- Track regressions across first-token latency, decode throughput, memory ceiling, and failure rate.

Definition of done:
- Every future optimization or policy change is gated by reproducible raw evidence.
- Promotion artifacts are sufficient for another engineer to reproduce the decision without guesswork.

## Comparative Systems Lead

Goal: use peer systems as calibration inputs, not as cargo-cult templates.

Sublead A: PocketPal parity pod
- Compare artifact choices, runtime defaults, and fallback behavior on overlapping device classes.

Sublead B: Desktop/runtime comparator pod
- Spot-check LM Studio and Ollama for transferable ideas around estimate-only flows, backend surfacing, and artifact curation.

Definition of done:
- Comparator work yields concrete accept/reject decisions for Pocket GPT policy rather than generic notes.
- Any borrowed pattern is mapped to a Pocket GPT code owner and validation method.

## External Research Lead

Goal: keep the optimization program aligned with upstream runtime reality.

Sublead A: Upstream runtime pod
- Track `llama.cpp` and related runtime changes relevant to speculative decoding, KV cache strategy, backend support, and Android acceleration.

Sublead B: Papers and best-practices pod
- Maintain the shortlist of research and vendor guidance that should influence policy updates.
- Separate production-grade recommendations from research-only experiments.

Definition of done:
- The team can justify each advanced optimization lane as either production-ready, experimental, or out of scope.
- Source-backed updates are attached to any future policy or backend graduation proposal.

## Remaining Work Inventory

1. Device qualification
- Run the supported-device matrix across CPU, OpenCL, and Hexagon paths and record which backend tiers are actually releasable.

2. Parameter calibration
- Benchmark and set evidence-backed policy values for threads, batch, micro-batch, GPU layers, KV-cache type, and speculative settings by device/model/backend.

3. Artifact policy
- Finalize the supported GGUF catalog, quantization tiers, and draft-model pairing map.

4. Routing and rollout
- Decide whether routing stays intentionally frozen or starts consuming benchmark evidence by release train.

5. Release qualification
- Publish the supported device/model/backend matrix and the regression gates required for future optimization changes.

## What Is Not Remaining

- The repo-side correctness repairs from the original backlog are no longer open.
- The remaining work is not another broad code sweep; it is evidence generation, policy calibration, and production qualification.
