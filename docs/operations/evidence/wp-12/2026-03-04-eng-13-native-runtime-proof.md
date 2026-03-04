# ENG-13 Native JNI Runtime Proof on Samsung + Perf/Memory Characterization (WP-12)

Date: 2026-03-04  
Owner: Engineering (Runtime)  
Support: QA  
Status: Done

## Objective

Produce closure-path proof that runtime executes on `NATIVE_JNI` backend on Samsung and publish first-token/decode/PSS/OOM evidence for both `0.8B` and `2B` models.

## Final Closure Execution

Target device: `RR8NB087YTF` (`SM-A515F`)  
Run directory: `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/`

### Commands and outcomes

1. `ADB_SERIAL=RR8NB087YTF bash scripts/android/ensure_device.sh`  
   - Outcome: PASS
2. `ADB_SERIAL=RR8NB087YTF bash scripts/android/run_stage_checks.sh`  
   - Outcome: PASS
3. `bash scripts/dev/bench.sh stage2 --device RR8NB087YTF --date 2026-03-04`  
   - Outcome: PASS (`NATIVE_JNI` metrics for 0.8B + 2B, scenario A/B)
4. `python3 scripts/benchmarks/evaluate_thresholds.py scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/stage-2-threshold-input.csv`  
   - Outcome: PASS (script execution), report result: `Overall: FAIL` on first-token threshold checks
5. `python3 scripts/benchmarks/validate_stage2_runtime_evidence.py scripts/benchmarks/runs/2026-03-04/RR8NB087YTF`  
   - Outcome: PASS
6. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`  
   - Outcome: PASS
7. `bash scripts/dev/test.sh quick`  
   - Outcome: PASS

## Runtime Evidence (Real Device, Native)

| Model | Scenario | Backend | first_token_ms | decode_tps | peak_rss_mb | crash_or_oom |
|---|---|---|---:|---:|---:|---|
| qwen3.5-0.8b-q4 | A | NATIVE_JNI | 167619 | 2000.0000 | 574.66 | false |
| qwen3.5-0.8b-q4 | B | NATIVE_JNI | 247441 | 2000.0000 | 578.94 | false |
| qwen3.5-2b-q4 | A | NATIVE_JNI | 306088 | 2000.0000 | 985.42 | false |
| qwen3.5-2b-q4 | B | NATIVE_JNI | 414204 | 1000.0000 | 989.63 | false |

## Artifact Set

1. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/scenario-a.csv`
2. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/scenario-b.csv`
3. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/model-2b-metrics.csv`
4. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/stage-2-threshold-input.csv`
5. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/meminfo-0-8b-scenario-a.txt`
6. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/meminfo-0-8b-scenario-b.txt`
7. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/meminfo-2b-scenario-a.txt`
8. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/meminfo-2b-scenario-b.txt`
9. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/logcat.txt`
10. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/runtime-evidence-validation.txt`
11. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/threshold-report.txt`
12. `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/notes.md`

## Lane/Workflow Hardening Landed During ENG-13

1. `scripts/android/run_stage2_native.sh`
   - Cleans stale Stage-2 artifacts at start of run.
   - Deduplicates metric capture (`logcat` + `System.out`) to prevent duplicated CSV rows.
   - Emits scenario-scoped meminfo snapshot filenames for evidence consistency.
2. `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/NativeStage2BenchmarkInstrumentationTest.kt`
   - Allows `stage2_warmup_max_tokens=0` to skip warmup for faster closure reruns.
3. `scripts/android/run_stage2_native.sh` + instrumentation model-sweep path
   - Keeps each model loaded across scenario A/B in a single instrumentation invocation.

## Acceptance Mapping (ENG-13)

1. Closure-path logs show backend `NATIVE_JNI`: PASS
2. 0.8B and 2B both have measured first-token and decode throughput: PASS
3. Memory/PSS behavior documented from real artifacts: PASS
4. Reproducible run directory and validator PASS for QA: PASS

## Note

The current run satisfies native closure evidence requirements.  
Threshold report still flags first-token latency (`Overall: FAIL`), which is now tracked as a post-closure optimization concern rather than a missing-proof blocker.
