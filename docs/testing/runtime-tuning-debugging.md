# Runtime Tuning Debugging Guide

Last updated: 2026-03-10

This guide explains how PocketGPT's Android runtime tuning works, how to trigger benchmark-backed promotions or demotions, and how to inspect the results after a device run.

## What The Tuner Does

The Android runtime tuning layer records per-device, per-model, per-profile, and per-mode results and adjusts the runtime toward stable settings.

It currently learns these controls:

1. `gpuLayers`
2. `speculativeEnabled`
3. `nBatch`
4. `nUbatch`
5. memory-pressure demotion signals via `peak_rss_mb`

The tuner never promotes beyond the current safe profile cap:

1. GPU promotions stop at the probe-qualified `gpuLayers` budget.
2. Batch promotions stop at the profile's safe batch values.
3. Speculative decoding is only re-enabled if the base profile allows it.

Promotion rule:

1. A promotion requires repeated benchmark-quality wins on the same `(device, model, profile, cpu/gpu mode)` key.
2. A benchmark-quality win means: success, no thermal throttling, safe memory (`peak_rss_mb`), acceptable first-token latency, and acceptable decode throughput.
3. After 3 benchmark-quality wins, the tuner promotes one step only.

Demotion rule:

1. GPU/runtime crashes demote GPU layers.
2. Memory pressure demotes GPU layers and batch sizes.
3. Slow or thermally unstable speculative decoding disables speculative mode.

## Where Results Show Up

There are two primary result surfaces.

### 1. Benchmark Lane Artifacts

Device benchmark artifacts live under:

- `scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/`

Important files to inspect first:

1. `summary.json`: top-level scenario/model metrics and run summary.
2. `scenario-a.csv` / `scenario-b.csv`: per-run timing rows.
3. `model-2b-metrics.csv`: 2B-specific metrics when collected.
4. `meminfo-*.txt`: Android memory snapshots for the benchmark run.
5. `logcat.txt`: runtime warnings, crashes, GPU probe behavior, and provisioning/runtime failures.
6. `threshold-report.txt`: threshold pass/fail output.
7. `runtime-evidence-validation.txt`: stage-2 runtime evidence contract result.
8. `evidence-draft.md`: human-readable evidence draft.

### 2. In-App Diagnostics Export

From the app:

1. Open the advanced settings sheet.
2. Tap `Export diagnostics`.
3. PocketGPT writes the diagnostics payload into the active chat as a system diagnostic message.
4. Copy that diagnostic message from the chat UI if you need to compare runs.

The diagnostics payload now includes:

1. `GPU_OFFLOAD|...`
2. `GPU_PROBE|...`
3. `RUNTIME_TUNING|...`
4. `RUNTIME_TUNING_SAMPLE|...`

## How To Trigger Useful Tuning Evidence

Use canonical flows. Do not invent ad-hoc one-off commands if the standard lanes already cover the path.

### Fast Confidence

```bash
bash scripts/dev/test.sh fast
```

### Repeated Real-Device Runtime Validation

```bash
python3 tools/devctl/main.py lane journey --repeats 3 --mode strict --reply-timeout-seconds 90
```

Use this when you want repeated sends against the same device/runtime configuration so the tuner can accumulate promotion wins.

### Stage-2 Benchmark Sweep

```bash
bash scripts/dev/bench.sh stage2 --device <device-id> --profile quick --models 0.8b --scenarios a
```

For closure-level evidence:

```bash
bash scripts/dev/bench.sh stage2 --device <device-id> --profile closure --models both --scenarios both
```

After the benchmark finishes:

1. export diagnostics from the app;
2. capture the `RUNTIME_TUNING|...` lines;
3. compare them with `summary.json`, `scenario-*.csv`, `meminfo-*.txt`, and `logcat.txt`.

## How To Read The Diagnostics

### `RUNTIME_TUNING|...`

This is the current recommendation for one `(device, model, profile, mode)` key.

Most important fields:

1. `recommended_gpu_layers`: the currently learned GPU-layer budget.
2. `target_gpu_layers`: the safe cap from the current profile + GPU qualification.
3. `recommended_speculative`: whether speculative decoding is currently allowed.
4. `recommended_n_batch` / `recommended_n_ubatch`: the currently learned batch sizes.
5. `benchmark_win_count`: repeated benchmark-quality wins accumulated since the last promotion or regression.
6. `promotion_count`: how many one-step promotions have occurred for this key.
7. `last_decision`: the most recent tuner action.
8. `last_first_token_ms`: latest recorded prefill/first-token metric for this key.
9. `last_tokens_per_sec`: latest recorded decode throughput for this key.
10. `last_peak_rss_mb`: latest recorded process RSS/HWM sample used by the tuner.
11. `success_count` / `failure_count`: aggregate run counts recorded for this key.

### `RUNTIME_TUNING_SAMPLE|...`

These are the most recent per-run observations for the same key.

Most important fields:

1. `success`
2. `decision`
3. `first_token_ms`
4. `tokens_per_sec`
5. `peak_rss_mb`
6. `thermal_throttled`
7. `error_code`
8. `applied_gpu_layers`
9. `applied_speculative`
10. `applied_n_batch` / `applied_n_ubatch`
11. `timestamp_epoch_ms`

Use the sample lines to answer: "what setting did we apply for the last few runs, and what did the tuner decide after seeing them?"

## Recommended Debug Workflow

1. Run `bash scripts/dev/test.sh fast`.
2. Run one real-device lane:
   - `python3 tools/devctl/main.py lane journey --repeats 3`
   - or `bash scripts/dev/bench.sh stage2 ...`
3. Export diagnostics from the app immediately after the run.
4. Search the diagnostics message for `RUNTIME_TUNING|` and `RUNTIME_TUNING_SAMPLE|`.
5. Open the matching benchmark artifact directory under `scripts/benchmarks/runs/...`.
6. Compare these files in order:
   - `summary.json`
   - `scenario-a.csv` / `scenario-b.csv`
   - `meminfo-*.txt`
   - `logcat.txt`
7. Decide whether the tuner behaved correctly:
   - correct demotion after GPU/runtime instability;
   - correct demotion under high memory pressure;
   - correct speculative disable under thermal/throughput regressions;
   - correct one-step promotion only after repeated stable wins.

## Common Interpretation Patterns

### GPU Was Demoted Unexpectedly

Check:

1. `RUNTIME_TUNING|last_decision=demote_gpu_regression`
2. `RUNTIME_TUNING_SAMPLE|error_code=...`
3. `logcat.txt` for Vulkan/JNI/remote-runtime failures
4. `GPU_OFFLOAD|probe_status=...`

### Batch Sizes Were Cut

Check:

1. `RUNTIME_TUNING|last_decision=demote_memory_pressure`
2. `RUNTIME_TUNING|last_peak_rss_mb=...`
3. `meminfo-*.txt` for PSS/RSS growth or system pressure

### Speculative Decoding Stayed Off

Check:

1. `RUNTIME_TUNING|recommended_speculative=false`
2. `RUNTIME_TUNING|last_decision=demote_speculative`
3. `RUNTIME_TUNING_SAMPLE|thermal_throttled=true`
4. `RUNTIME_TUNING_SAMPLE|first_token_ms=...`
5. `RUNTIME_TUNING_SAMPLE|tokens_per_sec=...`

### Promotion Did Not Happen

Check:

1. `benchmark_win_count`
2. recent `RUNTIME_TUNING_SAMPLE|...` lines for thermal throttling, memory pressure, or low throughput
3. whether `target_*` and `recommended_*` are already equal; in that case there is nothing left to promote

## Code Locations

The core implementation lives here:

1. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt`
2. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeGateway.kt`
3. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/ChatSendFlow.kt`
4. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModel.kt`
5. `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/SendMessageUseCase.kt`
6. `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt`
7. `apps/mobile-android/src/main/cpp/pocket_llama.cpp`

When debugging tuning behavior, start with diagnostics and artifacts first. Only then change heuristics.
