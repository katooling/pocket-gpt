# Stage-2 Benchmark Runbook (Scenario A/B)

Last updated: 2026-03-03

## Purpose

Prepare QA execution for WP-03 Stage-2 validation so Scenario A/B can run immediately after ENG-03 lands.

## Preconditions

1. ENG-03 merged and deployable to a physical Android device.
2. Device connected via `adb` and visible in `adb devices`.
3. Repo root is current working directory.
4. Benchmark CSV writer path in app outputs Scenario A/B rows matching the stage schema.

## Evidence Path Convention (Source of Truth)

Use this deterministic path per run:

`scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/`

Required files for Stage-2:

1. `scenario-a.csv`
2. `scenario-b.csv`
3. `stage-2-threshold-input.csv` (A/B rows combined)
4. `threshold-report.txt`
5. `logcat.txt`
6. `notes.md`

Reference templates are in `docs/operations/evidence/wp-03/templates/`.

## CSV Schema Required By Threshold Evaluator

`python3 scripts/benchmarks/evaluate_thresholds.py <benchmark_csv>` expects rows with at least:

1. `scenario` (`A` or `B` for Stage-2)
2. `first_token_ms` (numeric)
3. `decode_tps` (numeric)

If required columns are missing, the command exits `1` with `Schema Error`.

The standard stage template (recommended) columns are:

`date,platform,device_class,device_name,runtime,model,scenario,first_token_ms,decode_tps,peak_rss_mb,battery_drop_pct_10m,thermal_note,crash_or_oom`

## Execution Checklist

1. Create run directory for today/device.
2. Copy templates from `docs/operations/evidence/wp-03/templates/` into the run directory.
3. Clear app state and restart device thermal baseline.
4. Execute Scenario A runs and capture raw metrics in `scenario-a.csv`.
5. Execute Scenario B runs and capture raw metrics in `scenario-b.csv`.
6. Combine A/B rows into `stage-2-threshold-input.csv`.
7. Run threshold evaluation:

```bash
python3 scripts/benchmarks/evaluate_thresholds.py \
  scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/stage-2-threshold-input.csv \
  | tee scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/threshold-report.txt
```

8. Capture device logs for the same time window into `logcat.txt`.
9. Record environment + anomalies in `notes.md` (build SHA, model artifact, runtime, device battery/thermal context).
10. Attach evidence links in QA evidence note under `docs/operations/evidence/wp-03/`.

## Command Snippets (Physical Device)

Create run folder:

```bash
RUN_DATE="$(date +%F)"
DEVICE_ID="<device-id>"
RUN_DIR="scripts/benchmarks/runs/${RUN_DATE}/${DEVICE_ID}"
mkdir -p "${RUN_DIR}"
cp docs/operations/evidence/wp-03/templates/* "${RUN_DIR}/"
```

Capture logcat around the benchmark window:

```bash
adb -s "${DEVICE_ID}" logcat -d > "${RUN_DIR}/logcat.txt"
```

Combine Scenario A/B CSV rows for threshold input:

```bash
head -n 1 "${RUN_DIR}/scenario-a.csv" > "${RUN_DIR}/stage-2-threshold-input.csv"
tail -n +2 "${RUN_DIR}/scenario-a.csv" >> "${RUN_DIR}/stage-2-threshold-input.csv"
tail -n +2 "${RUN_DIR}/scenario-b.csv" >> "${RUN_DIR}/stage-2-threshold-input.csv"
```

## Completion Criteria (Prep)

1. Templates present and copy-ready.
2. Threshold command path verified.
3. Evidence paths documented and deterministic.
4. QA-02 remains prep-only until real ENG-03 output is available.
