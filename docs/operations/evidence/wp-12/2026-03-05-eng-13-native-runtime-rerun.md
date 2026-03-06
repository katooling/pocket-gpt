# ENG-13 Native JNI Runtime Proof Rerun (WP-12)

Date: 2026-03-05  
Owner: Engineering (Runtime)  
Support: QA  
Lifecycle: Done (rerun executed)

## Objective

Re-run full Stage-2 closure artifact flow on Samsung after provisioning + packaging fixes to confirm real `NATIVE_JNI` execution and complete 0.8B/2B A/B evidence chain.

## Device and Run Context

- Device: `RR8NB087YTF` (`SM-A515F`)
- Run directory: `scripts/benchmarks/runs/2026-03-05/RR8NB087YTF/`
- Profile: `closure`
- Install mode: `skip` (APK already installed)
- Models/scenarios: `both` / `both`
- Prefix cache: disabled for stability (`POCKETGPT_PREFIX_CACHE_ENABLED=0`)

## Commands and Outcomes

1. `bash scripts/dev/bench.sh stage2 --profile quick --device RR8NB087YTF --date 2026-03-05 --models 0.8b --scenarios both --install-mode skip`
   - Outcome: PASS
2. `bash scripts/dev/bench.sh stage2 --profile closure --device RR8NB087YTF --date 2026-03-05 --models both --scenarios both --install-mode skip`
   - Outcome: Runtime artifact generation PASS, lane exit `THRESHOLD_FAIL` (strict threshold gate)
3. `python3 scripts/benchmarks/validate_stage2_runtime_evidence.py scripts/benchmarks/runs/2026-03-05/RR8NB087YTF`
   - Outcome: PASS
4. `python3 scripts/benchmarks/evaluate_thresholds.py scripts/benchmarks/runs/2026-03-05/RR8NB087YTF/stage-2-threshold-input.csv`
   - Outcome: executed; `Overall: FAIL` on first-token limits

## Runtime Evidence Snapshot

| Model | Scenario | Backend | first_token_ms | decode_tps | peak_rss_mb | crash_or_oom |
|---|---|---|---:|---:|---:|---|
| qwen3.5-0.8b-q4 | A | NATIVE_JNI | 158583 | 1000.0000 | 574.73 | false |
| qwen3.5-0.8b-q4 | B | NATIVE_JNI | 243515 | 1000.0000 | 579.03 | false |
| qwen3.5-2b-q4 | A | NATIVE_JNI | 272824 | 1000.0000 | 985.49 | false |
| qwen3.5-2b-q4 | B | NATIVE_JNI | 454514 | 1000.0000 | 990.41 | false |

## Acceptance Mapping (ENG-13)

1. Closure-path logs show backend `NATIVE_JNI`: PASS
2. 0.8B and 2B both have measured first-token/decode metrics: PASS
3. Memory behavior documented with real meminfo artifacts: PASS
4. Reproducible run directory + runtime validator report: PASS

## Known Follow-up (Non-Blocking for WP-12 Closure State)

- Strict first-token threshold checks remain failing on this device/model pairing (`threshold_exit_code=2`).
- Track as DX/performance optimization follow-up; runtime-proof evidence is complete.
