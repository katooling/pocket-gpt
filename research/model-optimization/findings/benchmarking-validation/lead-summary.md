# Benchmarking And Validation Lead Summary

Status: Initial evidence-backed pass complete.

## Scope

- benchmark harnesses
- device matrix
- evidence workflows
- rollout and regression gates

## Findings

## What The Current Harness Already Proves

1. The stage-2 benchmark path is a real, structured artifact pipeline rather than an ad-hoc script.
   - Evidence:
     - `scripts/dev/bench.sh` delegates to `devctl lane stage2`.
     - `tools/devctl/lanes.py` orchestrates threshold evaluation, runtime-evidence validation, summary generation, and evidence-draft generation.
     - `config/devctl/stage2.yaml` defines a concrete required-file contract.

2. Native benchmark runs protect against two common false positives: runtime fallback and wrong-model fallback.
   - Evidence:
     - `NativeStage2BenchmarkInstrumentationTest` asserts backend is `NATIVE_JNI`.
     - The same test asserts the returned `modelId` matches the requested model.

3. The repo already has useful tuning observability beyond plain latency/TPS numbers.
   - Evidence:
     - `docs/testing/runtime-tuning-debugging.md` documents `RUNTIME_TUNING|...`, `RUNTIME_TUNING_SAMPLE|...`, `GPU_OFFLOAD|...`, `GPU_PROBE|...`, `MMAP|...`, `FLASH_ATTN|...`, `SPECULATIVE|...`, and `PREFIX_CACHE|...`.
     - `tools/devctl/runtime_log_signals.py` converts `MMAP|`, `FLASH_ATTN|`, `SPECULATIVE|`, and `PREFIX_CACHE|` log lines into machine-readable and markdown signal reports.
     - `tools/devctl/lanes.py` writes these reports for both journey and stage-2 artifacts.

4. GPU qualification is intentionally split by environment in a defensible way.
   - Evidence:
     - `docs/testing/gpu-qualification-split-plan.md` assigns cloud to UI/API-tier checks and real devices to actual GPU eligibility conclusions.
     - `scripts/dev/maestro-cloud-gpu-model-matrix.sh` fans out hosted qualification checks.
     - `scripts/dev/maestro-gpu-real-device-matrix.sh` requires explicit physical serials and model-aware progression on the same device.

5. Correctness and lifecycle protection are already stronger than benchmark protection.
   - Evidence:
     - `tools/devctl/gates.py` defines merge-unblock and promotion gates around `merge`, `doctor`, `android-instrumented`, `maestro`, `journey`, and lifecycle flow execution.
     - `docs/testing/test-strategy.md` treats stage-2 as a later physical-device closure lane, not a default promotion gate.

## What The Current Harness Does Not Prove Yet

1. It does not guarantee that benchmark metrics are meaningful when token budgets are overridden downward.
   - Evidence:
     - `scripts/android/run_stage2_native.sh` allows overrides for `max_tokens_a`, `max_tokens_b`, `min_tokens`, and warmup settings via CLI/env.
     - The stored closure run at `scripts/benchmarks/runs/2026-03-05/RR8NB087YTF/notes.md` records `Max tokens A/B: 2/2`.
     - That same run reports `decode_tps=1000.0000` in `scenario-a.csv` and `model-2b-metrics.csv`, which is not useful as real decode evidence.

2. The runtime evidence validator is materially weaker than the benchmark quality bar.
   - Evidence:
     - `scripts/benchmarks/validate_stage2_runtime_evidence.py` checks file presence, `NATIVE_JNI`, scenario rows, and positive metrics.
     - The historical run above shows `runtime-evidence-validation.txt: PASS` while `threshold-report.txt` is an obvious FAIL with first-token latencies in the hundreds of seconds.

3. Stage-2 is not wired into the default promotion path for optimization changes.
   - Evidence:
     - `tools/devctl/gates.py` promotion gate runs `android-instrumented`, `maestro`, and `journey`, but not `stage2`.
     - `docs/testing/test-strategy.md` frames stage-2 as weekly release rehearsal / physical-device signoff.

4. The current device matrix is qualification-oriented, not tuning-oriented.
   - Evidence:
     - `docs/testing/gpu-qualification-split-plan.md` is about whether GPU is eligible per model/device pair.
     - The real-device matrix scripts do not capture the same rich first-token / throughput / memory artifact set as stage-2 for each matrix point.
     - The cloud GPU-vs-CPU flow compares elapsed send time only.

5. The artifact contract has historical drift risk.
   - Evidence:
     - Current `config/devctl/stage2.yaml` requires `runtime-log-signals.json` and `runtime-log-signals.md`.
     - The stored run at `scripts/benchmarks/runs/2026-03-05/RR8NB087YTF/` does not contain those files.

## What Must Be Added Before Optimization Work Is Trustworthy

1. Closure runs need a locked minimum evidence floor.
   - At minimum:
     - minimum token budget
     - minimum run count
     - rejection of obviously implausible decode windows

2. Stage-2 needs raw per-run metrics, not median-only outputs.
   - Required additions:
     - run index
     - token count
     - requested max tokens
     - cold/warm marker
     - thermal state if available

3. Optimization-risk changes need an earlier benchmark gate.
   - Suggested policy:
     - `stage2 quick` for native/runtime/tuning changes
     - `stage2 closure` for release or branch-promotion signoff

4. The device matrix needs to expand from eligibility proof into tuning proof.
   - Add axes for:
     - performance profile
     - CPU/GPU mode
     - supported quant tiers
     - at least one negative-control device

5. Stage-2 coverage should expand beyond short-text A/B medians when optimization claims touch other paths.
   - Missing surfaces:
     - prefix-cache switch-back behavior
     - speculative on/off comparisons
     - image path
     - tool loop path

## Top-Priority Validation Gaps

1. The harness can currently produce closure-looking artifacts from underpowered benchmark settings.
2. Stage-2 is not part of the default promotion gate for optimization-sensitive changes.
3. Device qualification evidence is stronger than device tuning evidence.
4. Historical artifact outputs and current config expectations are not fully aligned.

## Files In This Pass

- `research/model-optimization/findings/benchmarking-validation/engineer-current-harness.md`
- `research/model-optimization/findings/benchmarking-validation/engineer-device-matrix.md`
- `research/model-optimization/findings/benchmarking-validation/engineer-acceptance-gates.md`
