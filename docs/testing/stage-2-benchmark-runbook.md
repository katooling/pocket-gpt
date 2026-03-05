# Stage-2 Benchmark Runbook (Scenario A/B)

Last updated: 2026-03-04

## Source of truth

- Command contract: `scripts/dev/README.md`
- Strategy/release gates: `docs/testing/test-strategy.md`

## Evidence Path Convention

Raw machine artifacts:

- `scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/`

Required files:

1. `scenario-a.csv`
2. `scenario-b.csv`
3. `stage-2-threshold-input.csv`
4. `model-2b-metrics.csv`
5. `meminfo-*.txt`
6. `threshold-report.txt`
7. `runtime-evidence-validation.txt`
8. `logcat.txt`
9. `notes.md`

## Recommended Execution

```bash
bash scripts/dev/bench.sh stage2 --profile closure --device <device-id>
```

Runner behavior note:

1. `scripts/android/run_stage2_native.sh` executes model-sweep instrumentation.
2. Each model is loaded once per sweep and reused for Scenario A/B in the same instrumentation invocation.
3. This reduces repeated load/unload overhead versus per-scenario process restarts.
4. Runner supports partial execution (`--models`, `--scenarios`) and resume mode (`--resume`) using run manifest state.
5. Runner deduplicates captured `STAGE2_METRIC` lines before writing CSV rows.
6. Runner writes metadata to `stage2-run-meta.env` for summary/evidence generation.

Required environment:

```bash
export POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH=/absolute/device/path/qwen3.5-0.8b-q4.gguf
export POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH=/absolute/device/path/qwen3.5-2b-q4.gguf
```

If models are only available on host storage, provision them to device first:

```bash
bash scripts/android/provision_sideload_models.sh \
  --device <device-id> \
  --model-0-8b-local <host-path-to-qwen3.5-0.8b-q4.gguf> \
  --model-2b-local <host-path-to-qwen3.5-2b-q4.gguf>
```

After run completion, runner generates `evidence-draft.md` in the run directory. Promote that draft into the correct `docs/operations/evidence/...` note path and add ticket-specific acceptance mapping.

Validate closure-path artifact integrity before evidence submission:

```bash
python3 scripts/benchmarks/validate_stage2_runtime_evidence.py \
  scripts/benchmarks/runs/YYYY-MM-DD/<device-id>
```

## Profiles

### Quick Profile (Engineering Iteration)

Use this profile for local iteration before final closure packet reruns:

```bash
bash scripts/dev/bench.sh stage2 \
  --profile quick \
  --device <device-id> \
  --models 0.8b \
  --scenarios both \
  --install-mode auto \
  --resume
```

Quick profile characteristics:

1. Defaults to 0.8B model, low token/runs configuration.
2. Supports partial scenarios (`a` or `b`) for targeted reruns.
3. Supports resume to avoid repeating completed sweeps.
4. Uses filtered logcat by default.

### Closure Profile (Gate/Signoff)

```bash
bash scripts/dev/bench.sh stage2 \
  --profile closure \
  --device <device-id> \
  --models both \
  --scenarios both \
  --install-mode auto
```

Closure profile requirements:

1. Must produce full artifact contract (`model-2b-metrics.csv`, meminfo snapshots, threshold/runtime reports).
2. Must pass strict threshold evaluation.
3. Must pass runtime evidence validator (`NATIVE_JNI` required, `ADB_FALLBACK` forbidden).
4. Must be linked from dated evidence note under `docs/operations/evidence/...`.
