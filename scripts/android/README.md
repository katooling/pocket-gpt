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
- `run_stage2_native.sh`: executes real Stage-2 native JNI benchmark runs on-device (supports profile-driven quick/closure execution, partial model/scenario runs, resume manifest, install caching, and run metadata output)
- `provision_sideload_models.sh`: pushes local GGUF models with SHA-aware skip behavior and emits export-ready env files under `scripts/benchmarks/device-env/`

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
bash scripts/android/run_stage2_native.sh --device <device-id> --profile quick --models 0.8b --scenarios both --resume --install-mode auto
```

Fast-iteration runtime controls:

- `POCKETGPT_STAGE2_SKIP_INSTALL=1`
- `POCKETGPT_STAGE2_RUNS=<n>`
- `POCKETGPT_STAGE2_MAX_TOKENS_A=<n>`
- `POCKETGPT_STAGE2_MAX_TOKENS_B=<n>`
- `POCKETGPT_STAGE2_MIN_TOKENS=<n>`
- `POCKETGPT_STAGE2_WARMUP_MAX_TOKENS=<n>`
- `POCKETGPT_STAGE2_MODEL_PROVISION_SKIPPED=<0|1|unknown>`
- `POCKETGPT_PREFIX_CACHE_ENABLED=<0|1>`
- `POCKETGPT_PREFIX_CACHE_STRICT=<0|1>`

The second parameter in `collect_logcat.sh` is capture duration in seconds.

`run_short_loop.sh` is command-agnostic; use it with the stage command you want to validate.

Caching outputs:

- Model provisioning metadata: `scripts/benchmarks/cache/<device>/model-provision-state.env`
- APK install metadata: `scripts/benchmarks/cache/<device>/apk-install-state.env`
- Device export env file: `scripts/benchmarks/device-env/<device>.env`

Stage-2 cache telemetry:

- `run_stage2_native.sh` collects `PREFIX_CACHE|...` counters from logcat and stores rollup values in `stage2-run-meta.env`.
- Quick profile fails fast if cache counters are missing while prefix cache is enabled.
- Summary output fields include `prefix_cache_hits`, `prefix_cache_misses`, `prefill_tokens_reused`, and `warm_vs_cold_first_token_delta_ms`.
