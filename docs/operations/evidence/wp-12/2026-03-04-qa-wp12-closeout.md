# QA-WP12 Closeout Validation Packet (ENG-12..ENG-17)

Date: 2026-03-04  
Owner: QA Engineer  
Lifecycle: Complete (Recommendation: Close WP-12)

## Objective

Re-run WP-12 validation against landed ENG evidence for `ENG-12..ENG-17` and issue final QA recommendation.

## Commands Run and Outcomes

1. `ADB_SERIAL=DEVICE_SERIAL_REDACTED bash scripts/android/ensure_device.sh`  
   - Outcome: PASS
2. `ADB_SERIAL=DEVICE_SERIAL_REDACTED bash scripts/android/run_stage_checks.sh`  
   - Outcome: PASS
3. `bash scripts/dev/bench.sh stage2 --device DEVICE_SERIAL_REDACTED --date 2026-03-04`  
   - Outcome: PASS (real `NATIVE_JNI` packet with 0.8B + 2B scenario A/B metrics and meminfo snapshots)
4. `python3 scripts/benchmarks/evaluate_thresholds.py scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/stage-2-threshold-input.csv`  
   - Outcome: PASS (script execution), report indicates first-token threshold failures
5. `python3 scripts/benchmarks/validate_stage2_runtime_evidence.py scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED`  
   - Outcome: PASS

## Referenced ENG Evidence

1. `docs/operations/evidence/wp-12/2026-03-04-eng-12-model-distribution-implementation.md`
2. `docs/operations/evidence/wp-12/2026-03-04-eng-13-native-runtime-proof.md`
3. `docs/operations/evidence/wp-12/2026-03-04-eng-14-android-native-memory.md`
4. `docs/operations/evidence/wp-12/2026-03-04-eng-15-tool-store-integration.md`
5. `docs/operations/evidence/wp-12/2026-03-04-eng-16-image-runtime-path.md`
6. `docs/operations/evidence/wp-12/2026-03-04-eng-17-network-policy-wiring.md`

## Raw Artifact Directory

- `scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/`

## QA Decision

1. `ENG-12`, `ENG-13`, `ENG-14`, `ENG-15`, `ENG-16`, and `ENG-17` evidence is complete and reproducible.
2. Closure-path backend proof now shows `NATIVE_JNI` and includes both model classes (`0.8B`, `2B`) with memory snapshots.
3. Recommendation: **Close WP-12**.

## Follow-up Risk Note (Non-Blocking for WP-12 Closure)

1. Stage-2 threshold report shows high first-token latency on this device profile.
2. Track this as a performance optimization follow-up lane after WP-12 closure.
