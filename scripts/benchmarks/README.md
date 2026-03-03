# Benchmark Harness (Phase 0)

This folder contains scripts/templates to run and record feasibility spikes.

## Files

- `result-template.csv`: manual metric capture template
- `aggregate.py`: aggregates CSV results and computes summary by model/runtime/device class

## Usage

1. Copy `result-template.csv` to a new run file per test session.
2. Fill one row per scenario execution.
3. Run:

```bash
python3 scripts/benchmarks/aggregate.py scripts/benchmarks/result-template.csv
```

4. Paste summary output into `docs/feasibility/spike-results.md`.
