# ENG-13 Native JNI Runtime Proof on Samsung + Perf/Memory Characterization (WP-12)

Date: 2026-03-04
Owner: Engineering (Runtime)
Support: QA
Status: In Progress (execution-ready; awaiting side-load model paths)

## Objective

Produce closure-path proof that runtime executes on `NATIVE_JNI` backend on Samsung device and publish first-token/decode/PSS/OOM evidence for both `0.8B` and `2B`.

## Command Contract Execution

1. `ADB_SERIAL=RR8NB087YTF bash scripts/android/ensure_device.sh`
   - Outcome: PASS
2. `ADB_SERIAL=RR8NB087YTF bash scripts/android/run_stage_checks.sh`
   - Outcome: PASS
3. `./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug`
   - Outcome: PASS
   - Verified APK contains `lib/arm64-v8a/libpocket_llama.so`.
4. `bash scripts/dev/bench.sh stage2 --device RR8NB087YTF --date 2026-03-04`
   - Outcome: FAIL (expected fail-fast)
   - Reason: missing required side-load env vars:
     - `POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH`
     - `POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH`
5. `python3 scripts/benchmarks/evaluate_thresholds.py scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/stage-2-threshold-input.csv`
   - Outcome: PASS
6. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS
7. `bash scripts/dev/test.sh quick`
   - Outcome: PASS

## Artifacts

1. Native packaging proof command output:
   - `unzip -l apps/mobile-android/build/outputs/apk/debug/mobile-android-debug.apk | rg libpocket_llama.so`
2. New native Stage-2 runner and lane wiring:
   - `scripts/android/run_stage2_native.sh`
   - `tools/devctl/lanes.py` (`lane stage2` now executes real native runner + runtime evidence validator)
3. Stage-2 runtime evidence validator:
   - `scripts/benchmarks/validate_stage2_runtime_evidence.py`

## Blockers Against Acceptance

1. Device-side model artifact paths are not provisioned in the current shell session (`POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH`, `POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH` missing), so Stage-2 native run cannot execute yet.
2. Until those paths are set to real verified GGUF files on device storage, closure packet cannot produce:
   - `NATIVE_JNI` run logs for both `0.8B` and `2B`
   - fresh `model-2b-metrics.csv`
   - fresh `meminfo-*.txt` snapshots for both models/scenarios

## Resolution Needed

1. Export both side-load model paths to real on-device files:
   - `export POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH=<device-absolute-path>`
   - `export POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH=<device-absolute-path>`
2. Re-run:
   - `bash scripts/dev/bench.sh stage2 --device RR8NB087YTF --date 2026-03-04`
3. Validate and publish:
   - `python3 scripts/benchmarks/validate_stage2_runtime_evidence.py scripts/benchmarks/runs/2026-03-04/RR8NB087YTF`
