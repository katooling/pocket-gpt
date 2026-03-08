# Android Script Helpers

Source of truth for runnable command syntax: `scripts/dev/README.md`.

These scripts are internal implementation details behind `devctl` lanes and wrappers.
They may change without notice and are not a stable contributor-facing interface.

## What Lives Here

- device preflight helpers (`ensure_device.sh`, `run_stage_checks.sh`)
- device baseline/log capture helpers (`capture_device_baseline.sh`, `collect_logcat.sh`)
- benchmark environment helpers (`configure_device_for_benchmark.sh`)
- stage-2/provisioning internals (`run_stage2_native.sh`, `provision_sideload_models.sh`)
- local iteration helper (`dev_loop.sh`)

## Usage Policy

1. Use `scripts/dev/*` wrappers or `python3 tools/devctl/main.py lane ...` for normal workflows.
2. Call scripts in this folder directly only for debugging lane internals.
3. If behavior differs from docs, treat `scripts/dev/README.md` as canonical and update internals accordingly.
