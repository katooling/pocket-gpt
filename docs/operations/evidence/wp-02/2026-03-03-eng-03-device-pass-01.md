# ENG-03 (WP-02) Device Pass 01 - 2026-03-03

## Context

USB debugging was enabled on the attached Samsung device and the phone became visible to `adb`.

## Commands Run and Outcomes

1. `adb kill-server; adb start-server; adb devices -l`
   - Outcome: PASS (device detected)
   - Device: `RR8NB087YTF` (`SM-A515F`, Android 13)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-adb-devices.log`

2. `bash scripts/android/ensure_device.sh`
   - Outcome: PASS

3. `bash scripts/android/run_stage_checks.sh`
   - Outcome: PASS (battery + thermal + identity captured)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-stage-checks-success.log`

4. `bash scripts/android/capture_device_baseline.sh`
   - Outcome: PASS
   - Capture log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-device-baseline-capture.log`
   - Baseline file: `docs/operations/evidence/wp-02/device-baselines/20260303-194908-RR8NB087YTF-baseline.txt`

5. `bash scripts/android/configure_device_for_benchmark.sh status`
   - Outcome: PASS (pre-run settings captured)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-benchmark-config-before.log`

6. `bash scripts/android/configure_device_for_benchmark.sh apply`
   - Outcome: PASS (benchmark settings applied)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-benchmark-config-applied.log`

7. `bash scripts/android/run_short_loop.sh --runs 10 --label stage-run-host -- ./gradlew --no-daemon :apps:mobile-android:run`
   - Outcome: PARTIAL
   - Loop summary: all 10 command invocations exited `0`; crash/OOM scanner found no crash/OOM signals in collected logcat files.
   - Critical finding: each run prints `Startup checks failed: Failed to load baseline runtime model: qwen3.5-0.8b-q4.`
   - Summary file: `docs/operations/evidence/wp-02/runs/2026-03-03/RR8NB087YTF/stage-run-host-20260303-194922/summary.csv`
   - Loop log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-short-loop-stage-run.log`

8. `bash scripts/android/configure_device_for_benchmark.sh reset`
   - Outcome: PASS (device settings restored)
   - Log: `docs/operations/evidence/wp-02/2026-03-03-eng-03-benchmark-config-reset.log`

## Acceptance Criteria Check (WP-02)

1. Scenario A runs on physical Android device: NOT MET.
   - The loop command did not reach Scenario A due startup runtime model load failure.
2. No crash/OOM across 10 short runs: INCONCLUSIVE for Scenario A runtime path.
   - No crash/OOM was detected in the host-loop runs, but the intended runtime scenario did not execute.
3. Test command passes (`bash scripts/dev/verify.sh`): MET (previous evidence remains valid).

## Next Required Step

To close WP-02, we still need a true on-device Scenario A execution path from the current runtime bridge build, with 10 successful short runs and metrics/log evidence.
