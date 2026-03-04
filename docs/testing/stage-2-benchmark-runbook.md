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

Required environment:

```bash
export POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH=/absolute/device/path/qwen3.5-0.8b-q4.gguf
export POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH=/absolute/device/path/qwen3.5-2b-q4.gguf
```

After run completion, add a human evidence note in `docs/operations/evidence/wp-03/` linking the generated run directory.

Validate closure-path artifact integrity before evidence submission:

```bash
python3 scripts/benchmarks/validate_stage2_runtime_evidence.py \
  scripts/benchmarks/runs/YYYY-MM-DD/<device-id>
```
