# Stage-2 Evidence Hardening (Lead A) - 2026-03-27

## Scope
- `scripts/android/run_stage2_native.sh`
- `scripts/benchmarks/evaluate_thresholds.py`
- `config/devctl/stage2.yaml`

## Changes Applied
- Enforced closure profile minimums for `runs`, `max-tokens-a`, `max-tokens-b`, `min-tokens`, and `warmup-max-tokens`.
- Extended stage-2 runtime CSV rows/header to include `tokens` and `runs`.
- Switched `tokens`/`runs` evidence attribution to `STAGE2_METRIC` values with guarded fallback to configured values only when metrics omit those keys.
- Hardened threshold evaluation to validate Scenario A/B floors for `tokens` and `runs` when those columns are present, while remaining backward-compatible with older CSVs.
- Updated `config/devctl/stage2.yaml` `threshold_csv.columns` to require `tokens` and `runs`.

## Residual Gaps / Notes
- No fixture updates were required in `tools/devctl/tests` for this change set; targeted unit coverage for lanes/runtime evidence remained green.
