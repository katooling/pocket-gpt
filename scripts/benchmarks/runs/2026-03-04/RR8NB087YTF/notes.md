# Stage-2 Run Notes

- Date: 2026-03-04
- Device: RR8NB087YTF (Samsung SM-A515F, Android 13)
- Build SHA: local workspace (QA closeout rerun after ENG-04 closeout)
- Model artifact: qwen3.5-0.8b-q4 (artifact-manifest startup validation path active)
- Runtime: llama.cpp bridge via Android host runtime lane
- Scenario A run count: 1
- Scenario B run count: 1
- Battery start/end: stage-check level 85% (USB powered during run)
- Thermal baseline: stage-check status 0; AP 35.3C, BAT 34.0C, SKIN 34.6C
- Notable observations: initial host run failed until checksum env vars were set; rerun succeeded with startup checks passing and Stage A/B PASS metrics.
- Crash/OOM observed: none

Run metadata
- Source stage log: scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/qa-02-closeout-20260304-000113/stage-run.log
- Scenario A first_token_ms: 65
- Scenario A decode_tps: 64.51612903225806
- Scenario B first_token_ms: 62
- Scenario B decode_tps: 65.57377049180327
