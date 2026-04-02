# Engineer Note: Device Matrix And Experiment Surface

## Topic

Current device-matrix and environment strategy for benchmark and GPU validation.

## Current State

Pocket GPT intentionally splits proof by environment instead of asking one lane to prove everything.

Current documented split:

1. Maestro Cloud
   - Android UI/API-tier coverage
   - hosted clean-install GPU qualification surface checks
   - supplemental GPU-vs-CPU elapsed-time benchmark on cloud devices
2. Real Android devices
   - model-aware GPU qualification
   - exact device serial targeting
   - explicit same-device progression from smaller to larger models
3. `devctl lane stage2`
   - native runtime benchmark artifact generation on a chosen device
4. `devctl lane journey`
   - repeated real-device send evidence with runtime diagnostics

Key sources:

- `docs/testing/gpu-qualification-split-plan.md`
- `scripts/dev/maestro-cloud-gpu-model-matrix.sh`
- `scripts/dev/maestro-gpu-real-device-matrix.sh`
- `scripts/dev/maestro-gpu-matrix-common.sh`
- `tests/maestro/shared/scenario-gpu-qualify-by-model.template.yaml`
- `tests/maestro-cloud/scenario-gpu-cpu-benchmark.yaml`
- `docs/testing/pocketpal-parity-benchmark.md`

## Evidence

### GPU Qualification Split

`docs/testing/gpu-qualification-split-plan.md` defines:

1. Cloud is only authoritative for UI/API-tier behavior.
2. Physical devices are authoritative for GPU eligibility conclusions.
3. Qualification must remain model-aware if a smaller model passes and a larger model fails on the same device.

### Real-Device Matrix Script

`scripts/dev/maestro-gpu-real-device-matrix.sh`:

1. requires explicit `--serial`;
2. never auto-picks a device;
3. defaults to model keys `tiny`, `qwen_0_8b`, `qwen_2b`;
4. generates a dedicated Maestro flow per `(device, model)` pair.

### Cloud Matrix Script

`scripts/dev/maestro-cloud-gpu-model-matrix.sh`:

1. defaults to Android API levels `29`, `31`, and `34`;
2. uses the same generated qualification flow template;
3. is intended for hosted fan-out rather than driver-level truth.

### Cloud Benchmark Flow

`tests/maestro-cloud/scenario-gpu-cpu-benchmark.yaml`:

1. provisions the model if needed;
2. enables GPU and measures send elapsed time;
3. creates a new session, disables GPU, and measures CPU elapsed time;
4. asserts GPU is faster than CPU on the same hosted device.

### Comparator Surface

`docs/testing/pocketpal-parity-benchmark.md` defines a same-device comparison against PocketPal with ratio checks for:

- `decode_tps`
- `first_token_ms` when available

## Risk Or Gap

1. The current matrix is strong on GPU eligibility, but weak on optimization tuning coverage.
2. The documented device families are qualitative recommendations, not a formal support matrix with ownership and minimum sample counts.
3. The cloud benchmark flow measures wall-clock send duration only; it does not capture the same first-token, throughput, and memory artifacts as stage-2.
4. The real-device GPU matrix focuses on qualification success, not profile tuning across:
   - `BATTERY` / `BALANCED` / `FAST`
   - speculative on/off
   - prefix cache on/off
   - quant tier variants
   - thermally stressed vs cooled state
5. There is no clearly documented negative-control matrix requirement for devices that should remain ineligible.

## Recommendation

1. Formalize the tuning matrix with explicit axes:
   - device family
   - Android/API level
   - model and quantization
   - CPU/GPU mode
   - performance profile
   - cache/speculative settings when relevant
2. Keep cloud lanes for UI/API regression and quick hosted sanity only.
3. Require real-device quantitative follow-up for every `(device, model)` pair that qualifies for GPU.
4. Add at least one explicit negative-control device to every GPU qualification cycle.
5. Add a benchmark slice that couples qualification with performance evidence on the same device after qualification passes.
6. Use PocketPal parity selectively on representative devices rather than treating it as a universal gate.

## Validation Needed

1. Define the minimum device families required for optimization rollout signoff.
2. Prove that a qualified GPU path also improves user-visible latency or throughput on that exact pair.
3. Confirm that unsupported devices stay unsupported with clear reasons.

## Open Questions

1. What is the smallest physical-device set that still protects Adreno, Mali/Exynos, and unsupported classes?
2. Which optimization changes should trigger the full matrix versus a reduced matrix?
3. Should PocketPal parity be mandatory for OpenCL/Hexagon-related changes only?
