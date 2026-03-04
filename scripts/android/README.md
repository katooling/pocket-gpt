# Android Script Helpers

Source of truth for dev/test command contracts: `scripts/dev/README.md`.
Status: Internal implementation details for `devctl` lanes. These script interfaces are not stable and may change without notice.

These scripts support repeatable Android device testing for MVP stages.

## Scripts

- `run_stage_checks.sh`: runs quick adb checks and prints environment state
- `collect_logcat.sh`: captures filtered logs for a package tag window
- `ensure_device.sh`: validates one adb-ready device (supports `ADB_SERIAL`)
- `capture_device_baseline.sh`: captures device + battery + thermal + memory baseline
- `configure_device_for_benchmark.sh`: benchmark-friendly device settings (`status|apply|reset`)
- `run_short_loop.sh`: runs any command `N` times and captures crash/OOM signals from logcat
- `run_stage2_native.sh`: executes real Stage-2 native JNI benchmark runs on-device (model-sweep path keeps a model loaded across Scenario A/B per sweep; cleans stale Stage-2 artifacts; deduplicates metrics; emits 0.8B + 2B + meminfo/logcat artifacts)
- `provision_sideload_models.sh`: pushes local GGUF models to device storage and prints export-ready side-load env vars

## Usage

```bash
bash scripts/android/run_stage_checks.sh
bash scripts/android/collect_logcat.sh com.pocketagent 120
bash scripts/android/ensure_device.sh
bash scripts/android/capture_device_baseline.sh
bash scripts/android/configure_device_for_benchmark.sh status
bash scripts/android/configure_device_for_benchmark.sh apply
bash scripts/android/run_short_loop.sh --runs 10 --label wp02 -- bash scripts/dev/verify.sh
bash scripts/android/provision_sideload_models.sh --device <device-id> --model-0-8b-local <host-path> --model-2b-local <host-path>
bash scripts/android/run_stage2_native.sh --device <device-id>
```

Fast-iteration runtime controls:

- `POCKETGPT_STAGE2_SKIP_INSTALL=1`
- `POCKETGPT_STAGE2_RUNS=<n>`
- `POCKETGPT_STAGE2_MAX_TOKENS_A=<n>`
- `POCKETGPT_STAGE2_MAX_TOKENS_B=<n>`
- `POCKETGPT_STAGE2_MIN_TOKENS=<n>`
- `POCKETGPT_STAGE2_WARMUP_MAX_TOKENS=<n>`

The second parameter in `collect_logcat.sh` is capture duration in seconds.

`run_short_loop.sh` is command-agnostic; use it with the stage command you want to validate.
