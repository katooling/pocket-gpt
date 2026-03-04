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
bash scripts/dev/bench.sh stage2 --device <device-id>
```

Runner behavior note:

1. `scripts/android/run_stage2_native.sh` executes model-sweep instrumentation.
2. Each model is loaded once per sweep and reused for Scenario A/B in the same instrumentation invocation.
3. This reduces repeated load/unload overhead versus per-scenario process restarts.
4. Runner cleans prior Stage-2 artifacts in the target run directory before each invocation.
5. Runner deduplicates captured `STAGE2_METRIC` lines before writing CSV rows.

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

After run completion, add a human evidence note in `docs/operations/evidence/wp-03/` linking the generated run directory.

Validate closure-path artifact integrity before evidence submission:

```bash
python3 scripts/benchmarks/validate_stage2_runtime_evidence.py \
  scripts/benchmarks/runs/YYYY-MM-DD/<device-id>
```

## Fast Iteration Profile (Engineering)

Use this profile for local iteration before final closure packet reruns:

```bash
export POCKETGPT_STAGE2_SKIP_INSTALL=1
export POCKETGPT_STAGE2_RUNS=1
export POCKETGPT_STAGE2_MAX_TOKENS_A=4
export POCKETGPT_STAGE2_MAX_TOKENS_B=4
export POCKETGPT_STAGE2_MIN_TOKENS=1
export POCKETGPT_STAGE2_WARMUP_MAX_TOKENS=0
```

Then run:

```bash
bash scripts/dev/bench.sh stage2 --device <device-id>
```

When publishing closure evidence, rerun with closure-approved token/runs settings and include those values in `notes.md`.
