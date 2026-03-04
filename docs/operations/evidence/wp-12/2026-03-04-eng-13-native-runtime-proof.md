# ENG-13 Native JNI Runtime Proof on Samsung + Perf/Memory Characterization (WP-12)

Date: 2026-03-04
Owner: Engineering (Runtime)
Support: QA
Status: Blocked

## Objective

Produce closure-path proof that runtime executes on `NATIVE_JNI` backend on Samsung device and publish first-token/decode/PSS/OOM evidence for both `0.8B` and `2B`.

## Command Contract Execution

1. `ADB_SERIAL=RR8NB087YTF bash scripts/android/ensure_device.sh`
   - Outcome: PASS
2. `ADB_SERIAL=RR8NB087YTF bash scripts/android/run_stage_checks.sh`
   - Outcome: PASS
3. `bash scripts/dev/bench.sh stage2 --device RR8NB087YTF --date 2026-03-04`
   - Outcome: FAIL (`THRESHOLD_FAIL`; default template scenario CSVs produced zero decode metrics)
4. `bash scripts/dev/bench.sh stage2 --device RR8NB087YTF --date 2026-03-04 --scenario-a scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/qa-02-closeout-20260304-000113/scenario-a.csv --scenario-b scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/qa-02-closeout-20260304-000113/scenario-b.csv`
   - Outcome: PASS
5. `python3 scripts/benchmarks/evaluate_thresholds.py scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/stage-2-threshold-input.csv`
   - Outcome: PASS
6. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS
7. `bash scripts/dev/test.sh quick`
   - Outcome: PASS
8. `./gradlew --no-daemon :apps:mobile-android-host:run`
   - Outcome: FAIL (expected by truth gate)
   - Runtime output:
     - `Runtime backend: ADB_FALLBACK`
     - `Startup checks failed: Runtime backend is ADB_FALLBACK... Native JNI runtime is required...`

## Artifacts

1. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/scenario-a.csv`
2. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/scenario-b.csv`
3. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/stage-2-threshold-input.csv`
4. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/threshold-report.txt`
5. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/logcat.txt`

## Blockers Against Acceptance

1. No closure-path evidence showing backend `NATIVE_JNI`; current backend in proof lane is `ADB_FALLBACK`.
2. No integrated `2B` runtime benchmark evidence in current stage-2 packet.
3. PSS/OOM characterization is incomplete for ENG-13 target scope.

## Resolution Needed

1. Land Android ARM native JNI runtime artifact path (`pocket_llama` library loadable in closure lane).
2. Re-run stage-2 for both `0.8B` and `2B` with explicit backend evidence `NATIVE_JNI`.
3. Capture memory/PSS snapshots and failure-signal scans in the same dated run packet.
