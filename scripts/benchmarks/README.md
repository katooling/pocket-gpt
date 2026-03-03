# Benchmark Harness (Phase 0)

This folder contains scripts/templates to run and record feasibility spikes.

## Files

- `result-template.csv`: manual metric capture template
- `aggregate.py`: aggregates CSV results and computes summary by model/runtime/device class
- `evaluate_thresholds.py`: validates CSV schema and compares scenario medians against MVP thresholds
- `stage-scenarios-template.csv`: template for Scenario A/B/C threshold evaluation

## Usage

1. Copy `result-template.csv` to a new run file per test session.
2. Fill one row per scenario execution.
3. Run:

```bash
python3 scripts/benchmarks/aggregate.py scripts/benchmarks/result-template.csv
```

4. Paste summary output into `docs/feasibility/spike-results.md`.

5. Evaluate stage thresholds:

```bash
python3 scripts/benchmarks/evaluate_thresholds.py scripts/benchmarks/stage-scenarios-template.csv
```

Required columns for threshold evaluation:

- `scenario`
- `first_token_ms`
- `decode_tps`
