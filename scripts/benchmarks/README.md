# Benchmark Harness

Source of truth for benchmark command syntax and lane policy:

- `scripts/dev/README.md`
- `docs/testing/test-strategy.md`
- `docs/testing/runbooks.md`
- `docs/testing/runtime-tuning-debugging.md`

## Purpose

This directory contains benchmark utilities used by stage-2 and journey evidence workflows.

## Key Files

- `validate_stage2_runtime_evidence.py`: validates required stage-2 runtime artifact contract.
- `evaluate_thresholds.py`: threshold/schema validation utilities.
- `aggregate.py`: CSV aggregation helper.
- template fixtures (`result-template.csv`, `stage-scenarios-template.csv`, `mock-run.csv`).

## Artifact Contract

Stage-2 raw artifacts are written under:

- `scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/...`

Human-readable evidence belongs under:

- `docs/operations/evidence/...`

## Usage Policy

1. Run benchmark lanes via `bash scripts/dev/bench.sh ...`.
2. Use utilities in this folder for validation/post-processing, not as a replacement for lane orchestration.
