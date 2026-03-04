# QA-WP12 Closeout Validation Packet (ENG-12..ENG-17)

Date: 2026-03-04
Owner: QA Engineer
Status: Complete (Recommendation: Do Not Close WP-12)

## Objective

Re-run WP-12 validation against landed ENG evidence for `ENG-12..ENG-17` and issue final QA recommendation.

## Commands Run and Outcomes

1. `ADB_SERIAL=RR8NB087YTF bash scripts/android/ensure_device.sh`
   - Outcome: PASS
2. `ADB_SERIAL=RR8NB087YTF bash scripts/android/run_stage_checks.sh`
   - Outcome: PASS
3. `bash scripts/dev/bench.sh stage2 --device RR8NB087YTF --date 2026-03-04`
   - Outcome: FAIL (`THRESHOLD_FAIL`; template scenario files)
4. `bash scripts/dev/bench.sh stage2 --device RR8NB087YTF --date 2026-03-04 --scenario-a scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/qa-02-closeout-20260304-000113/scenario-a.csv --scenario-b scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/qa-02-closeout-20260304-000113/scenario-b.csv`
   - Outcome: PASS
5. `python3 scripts/benchmarks/evaluate_thresholds.py scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/stage-2-threshold-input.csv`
   - Outcome: PASS

## Referenced ENG Evidence

1. `docs/operations/evidence/wp-12/2026-03-04-eng-12-model-distribution-implementation.md`
2. `docs/operations/evidence/wp-12/2026-03-04-eng-13-native-runtime-proof.md`
3. `docs/operations/evidence/wp-12/2026-03-04-eng-14-android-native-memory.md`
4. `docs/operations/evidence/wp-12/2026-03-04-eng-15-tool-store-integration.md`
5. `docs/operations/evidence/wp-12/2026-03-04-eng-16-image-runtime-path.md`
6. `docs/operations/evidence/wp-12/2026-03-04-eng-17-network-policy-wiring.md`

## Raw Artifact Directory

- `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/`

## QA Decision

1. `ENG-12`, `ENG-14`, `ENG-15`, `ENG-16`, and `ENG-17` evidence is sufficient for their scoped acceptance.
2. `ENG-13` remains blocked: closure path still reports `ADB_FALLBACK` and does not provide required `NATIVE_JNI` proof with complete `0.8B` + `2B` + memory/PSS packet.
3. Recommendation: **Do not close WP-12** yet.

## Blockers

1. Owner: Runtime Engineering (`ENG-13`)
   - Required: `NATIVE_JNI` closure-lane proof on Samsung `RR8NB087YTF`.
   - Required: measured `0.8B` and `2B` throughput/latency and memory/PSS characterization in dated artifacts.
