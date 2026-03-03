# Stage-2 Benchmark Runbook (Scenario A/B)

Last updated: 2026-03-03

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
4. `threshold-report.txt`
5. `logcat.txt`
6. `notes.md`

## Recommended Execution

```bash
bash scripts/dev/bench.sh stage2 --device <device-id>
```

To use measured scenario files:

```bash
bash scripts/dev/bench.sh stage2 \
  --device <device-id> \
  --scenario-a <path-to-scenario-a.csv> \
  --scenario-b <path-to-scenario-b.csv>
```

After run completion, add a human evidence note in `docs/operations/evidence/wp-03/` linking the generated run directory.
