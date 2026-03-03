# Android DX and Test Playbook

Last updated: 2026-03-03

## Source of truth

- Command contract: `scripts/dev/README.md`
- Strategy/release gates: `docs/testing/test-strategy.md`

## Purpose

Define the Android development + physical-device validation workflow with minimal ambiguity.

## Environment Prerequisites

1. JDK 17+
2. Android SDK platform-tools (`adb`)
3. One physical Android device with USB debugging enabled
4. USB debugging RSA authorization accepted on-device

## Canonical Commands

Use only commands documented in `scripts/dev/README.md` for:

1. Standard local/CI tests
2. Physical-device lane
3. Stage-2 benchmark wrapper
4. Governance checks (`docs-drift-check`, `evidence-check`)

## Device Validation Loop

1. `bash scripts/android/ensure_device.sh`
2. `bash scripts/android/capture_device_baseline.sh`
3. `bash scripts/android/configure_device_for_benchmark.sh apply`
4. run scenario command using `scripts/android/run_short_loop.sh` or `scripts/dev/device-test.sh`
5. reset benchmark settings
6. store raw run artifacts under `scripts/benchmarks/runs/...`
7. link those run artifacts from `docs/operations/evidence/...` note

## Regression Rules (Fail Stage)

1. first-token latency regression beyond target band
2. new OOM or ANR in repeated runs
3. tool validation bypass or unsafe payload execution
4. policy allows network in offline-only mode
