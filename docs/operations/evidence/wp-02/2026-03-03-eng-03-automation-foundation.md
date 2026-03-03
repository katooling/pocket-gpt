# ENG-03 (WP-02) Automation Foundation Update - 2026-03-03

## Objective

Define and implement a low-complexity testing foundation that separates:

1. fully automatable checks (CI + CLI scripts), and
2. essential human-in-the-loop steps for physical-device reliability gates.

## Delivered

### 1) Script foundation for CLI-driven phone testing

Added:

- `scripts/android/ensure_device.sh`
- `scripts/android/capture_device_baseline.sh`
- `scripts/android/configure_device_for_benchmark.sh`
- `scripts/android/run_short_loop.sh`

Updated:

- `scripts/android/run_stage_checks.sh`
- `scripts/android/collect_logcat.sh`
- `scripts/android/README.md`

### 2) Documentation foundation

Added:

- `docs/testing/just-cli-android-validation-plan.md`

Updated:

- `docs/testing/test-strategy.md`
- `docs/testing/android-dx-and-test-playbook.md`
- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/roadmap/next-steps-execution-plan.md`

## Commands Run and Outcomes

1. `bash scripts/dev/verify.sh`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-foundation-verify.log`

2. `adb devices -l`
   - Outcome: FAIL for adb target discovery (`List of devices attached` empty)

3. `ioreg -p IOUSB -w0 -l | rg -n "SAMSUNG_Android|idVendor|idProduct" -i`
   - Outcome: PASS (macOS USB layer detects Samsung hardware)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-usb-hardware-detect.log`

4. `bash scripts/android/ensure_device.sh`
   - Outcome: FAIL (expected; no adb-authorized target yet)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-ensure-device.log`

5. `bash scripts/android/run_stage_checks.sh`
   - Outcome: FAIL (gated by `ensure_device.sh`)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-stage-checks-v2.log`

6. `bash scripts/android/configure_device_for_benchmark.sh status`
   - Outcome: FAIL (no adb-authorized target)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-benchmark-config-status.log`

7. `bash scripts/android/capture_device_baseline.sh`
   - Outcome: FAIL (no adb-authorized target)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-capture-baseline.log`

8. `bash scripts/android/run_short_loop.sh --runs 2 --label smoke -- bash scripts/dev/verify.sh`
   - Outcome: FAIL (gated by `ensure_device.sh`, no adb-authorized target)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-short-loop.log`

## Key Finding

The Samsung phone is visible to macOS at USB hardware level but not visible to adb. This is typically a phone-side debugging authorization/configuration gap (USB mode, developer options, RSA approval).

## Human-In-The-Loop Minimum (Cannot Be Removed)

1. Enable USB debugging and developer options.
2. Accept RSA debug authorization prompt.
3. Keep device in stable benchmark conditions (power + thermal).

## WP-02 Status Impact

- WP-02 remains `In Progress`.
- Runtime wiring + CI checks are in place.
- Physical-device Scenario A + 10-run crash/OOM evidence remains blocked until adb authorization succeeds.
