# Pocket-GPT vs PocketPal Parity Benchmark Checklist

Use this runbook when validating the OpenCL/Hexagon migration against PocketPal on the same Android device.

## Goal

Produce a reproducible comparison for:

1. Decode throughput (`decode_tps` / token generation tps)
2. First-token latency (when PocketPal export includes it)

## Preconditions

1. Use the same physical device for both apps.
2. Use the same model family and quantization in both apps (for example, `Q4_0` for OpenCL parity checks).
3. Keep device conditions stable:
   1. battery > 50%
   2. airplane mode on (unless download/setup is needed)
   3. no charging during measurement
   4. close background heavy apps
4. Confirm Pocket-GPT runtime backend details in the app:
   1. `Backend: NATIVE_JNI`
   2. runtime backend details card shows compiled accelerator backend(s)

## Step 1: Capture Pocket-GPT Metrics

Run a stage-2 benchmark slice and keep the generated `scenario-*.csv`:

```bash
bash scripts/dev/bench.sh stage2 \
  --device <adb-serial> \
  --profile quick \
  --models 0.8b \
  --scenarios a
```

Primary artifact:

1. `scripts/benchmarks/runs/YYYY-MM-DD/<serial>/scenario-a.csv`

## Step 2: Capture PocketPal Benchmark Output

1. On the same device, open PocketPal benchmark screen.
2. Run benchmark with matching model + backend profile.
3. Open share dialog and capture raw benchmark payload (`View raw data`).
4. Save payload to a file, for example:
   1. `tmp/pocketpal-benchmark.json`

Accepted PocketPal input formats for the compare script:

1. Raw JSON object with a `benchmark` object
2. JSON array of benchmark objects
3. CSV containing `decode_tps` (or `tgAvg`) and optional `first_token_ms`

## Step 3: Run Parity Comparison

```bash
python3 scripts/benchmarks/compare_pocketpal_parity.py \
  --pocket-gpt scripts/benchmarks/runs/YYYY-MM-DD/<serial>/scenario-a.csv \
  --pocketpal tmp/pocketpal-benchmark.json \
  --scenario A \
  --out-json scripts/benchmarks/runs/YYYY-MM-DD/<serial>/pocketpal-parity-report.json
```

Default checks:

1. `decode_tps` ratio (`Pocket-GPT / PocketPal`) must be `>= 0.80`
2. `first_token_ms` ratio (`Pocket-GPT / PocketPal`) must be `<= 1.25` when PocketPal exposes first-token latency

Tune thresholds for stricter gate:

```bash
python3 scripts/benchmarks/compare_pocketpal_parity.py \
  --pocket-gpt <scenario-csv> \
  --pocketpal <pocketpal-json-or-csv> \
  --min-tps-ratio 0.90 \
  --max-first-token-ratio 1.15
```

Exit codes:

1. `0`: pass
2. `1`: input/schema/data error
3. `2`: parity check failed

## Step 4: Investigate Failures

If parity fails:

1. verify Pocket-GPT runtime backend details card and diagnostics export include:
   1. requested backend profile
   2. compiled accelerator backends
   3. native accelerator support
2. confirm no `ADB_FALLBACK` backend on Pocket-GPT.
3. check runtime logs for `GPU_OFFLOAD|...` and `GPU_PROBE|...` markers.
4. rerun both benchmarks after cooling period to rule out thermal throttling.
