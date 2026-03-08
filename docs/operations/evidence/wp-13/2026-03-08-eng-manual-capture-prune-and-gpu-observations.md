# WP-13 Manual Capture Prune and GPU Observation Summary (2026-03-08)

Owner: Engineering  
Scope: Root-level manual PNG/XML capture cleanup after GPU/runtime investigation

## What Was Preserved vs Pruned

1. Raw manual captures were **not committed** to git.
2. All raw PNG/XML captures were archived locally under:
   - `scripts/benchmarks/runs/2026-03-08/manual-capture-prune/`
3. Distilled findings (below) are committed here as durable evidence.

Rationale:

1. Root-level ad-hoc captures create repository noise and are not a stable source of truth.
2. Raw artifacts are still preserved for local forensic follow-up in the benchmark runs area.
3. Repo-level evidence should store validated conclusions, not transient dumps.

## Distilled Findings from XML Dumps

Primary XML files reviewed:

1. `tmp-window_dump-advanced.xml`
2. `tmp-window_dump.xml`
3. `tmp_window_dump_adv_s22.xml`
4. `tmp_window_dump_advanced_sheet.xml`
5. `tmp_window_dump_after_skip.xml`
6. `tmp_window_dump_postlaunch.xml`
7. `tmp_window_dump_s22.xml`
8. `tmp_window_dump_gpu.xml`

Observed signals:

1. Non-S22 advanced-sheet capture set shows GPU control disabled with explicit copy:
   - `GPU acceleration unavailable on this build/device`
2. S22 advanced-sheet capture set shows GPU control available:
   - `Enable GPU acceleration`
3. S22 advanced-sheet capture set also shows optional routing entries:
   - `SMOLLM2_135M`
   - `SMOLLM2_360M`
4. Startup/main-screen dumps show `Backend: NATIVE_JNI` while blocked by model metadata/provisioning state:
   - `MODEL_ARTIFACT_CONFIG_MISSING:...`
   - `Runtime artifact metadata is missing... (UI-STARTUP-001)`
5. One dump (`tmp_window_dump_gpu.xml`) captured launcher/home UI rather than in-app runtime state and was excluded from runtime-behavior conclusions.

## Commit Policy Applied to Remaining Artifacts

1. Commit to repo: none of the raw PNG/XML files.
2. Keep outside git (archived): all raw PNG/XML manual captures in benchmark runs archive path.
3. Commit to repo for preservation: this evidence summary note only.
