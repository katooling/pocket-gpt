# ENG-03 (WP-02) Device Pass 02 - 2026-03-03

## Objective

Validate WP-02 acceptance criteria after adding runtime-bridge fallback execution through an adb-connected Android device and standardizing test entrypoints.

## Implementation Updates

1. Runtime bridge now supports a device-backed fallback path when native `pocket_llama` library is unavailable:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AndroidLlamaCppRuntimeBridge.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AdbDeviceLlamaCppRuntimeBridge.kt`
2. Standardized test entrypoints:
   - `scripts/dev/test.sh` (CI/local default)
   - `scripts/dev/device-test.sh` (physical-device lane)
   - `scripts/dev/verify.sh` retained as compatibility wrapper

## Commands Run and Outcomes

1. `bash scripts/dev/test.sh ci`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-test-command.log`

2. `bash scripts/dev/verify.sh`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-verify-wrapper.log`

3. `./gradlew --no-daemon :apps:mobile-android:run`
   - Outcome: PASS
   - Notable output includes successful model selection and scenario execution:
     - `Model: qwen3.5-0.8b-q4`
     - `Scenario A: StageBenchmarkResult(... pass=true)`
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-stage-run-after-fallback.log`

4. `bash scripts/dev/device-test.sh 10 wp02-close-rerun`
   - Outcome: PASS
   - Performs: device checks, baseline capture, benchmark config apply, 10-run loop, config reset.
   - Loop summary: all 10 runs exited `0`; `crash_detected=false`; `oom_detected=false`.
   - Main run log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-device-test-loop-rerun.log`
   - Summary: `docs/operations/evidence/wp-02/runs/2026-03-03/RR8NB087YTF/wp02-close-rerun-20260303-200256/summary.csv`

5. `bash scripts/android/configure_device_for_benchmark.sh status`
   - Outcome: PASS
   - Confirms settings restored after run (`stay_on_while_plugged_in=0`, animation scales reset to `1`).
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-benchmark-config-after-rerun.log`

## Acceptance Criteria Check (WP-02)

1. Scenario A runs on physical Android device: MET.
2. No crash/OOM across 10 short runs: MET.
3. Test command passes (`bash scripts/dev/verify.sh`): MET.

## Notes

- This pass validates the runtime bridge execution path with an adb-device fallback when native `llama.cpp` JNI library is not present on the host runtime.
- WP-03 can proceed as in-progress work; ENG-04 parallel execution remains valid.
