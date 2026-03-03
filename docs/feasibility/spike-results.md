# Phase 0 Feasibility Spike Results

## Evidence Status

Current numeric results in this file are from repository mock/template runs (`2026-03-03`), not from physical devices. They are useful for harness validation only and must not be used as final feasibility sign-off.

## Execution Status

Phase 0 benchmarking harness is implemented and validated in-repo.

Completed in this environment:

1. CSV result template created
2. Aggregation script implemented and executed
3. Mock benchmark run aggregated successfully

Pending for full hardware sign-off:

1. Run the benchmark protocol on representative physical iOS devices
2. Run the benchmark protocol on representative physical Android devices
3. Attach raw logs and thermal snapshots

## Harness Validation Output

Command:

```bash
python3 scripts/benchmarks/aggregate.py scripts/benchmarks/mock-run.csv
```

Output summary:

| platform | class | runtime | model | p50 first token (ms) | p50 tok/s | p95 RSS (MB) | battery drop (10m) | crashes |
|---|---|---|---|---:|---:|---:|---:|---:|
| iOS | mid | llama.cpp | qwen3.5-0.8b-q4 | 1100 | 12.4 | 1450 | 3.2% | 0 |
| iOS | mid | llama.cpp | qwen3.5-2b-q4 | 1900 | 6.2 | 2600 | 4.8% | 0 |
| Android | mid | llama.cpp | qwen3.5-0.8b-q4 | 1400 | 10.1 | 1620 | 3.9% | 0 |
| Android | mid | llama.cpp | qwen3.5-2b-q4 | 2350 | 4.4 | 2980 | 6.2% | 0 |

## Preliminary Recommendation

Based on protocol thresholds and mock data shape:

1. `0.8B` should be default on low/mid tiers.
2. `2B` should be enabled conditionally on mid/high tiers with thermal guardrails.
3. Routing policy must downgrade aggressively under heat or low battery.

## Go/No-Go Status

Current: **Conditional Go (Pending Real Device Confirmation)**

Rationale:

- Tooling and protocol are ready.
- Full feasibility sign-off requires real-device runs and raw evidence.
