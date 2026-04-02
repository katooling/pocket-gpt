# Lead E Execution Note: GPU Batch Default/Runtime Cap Alignment

Date: 2026-03-27

Decision:
- Aligned `PerformanceRuntimeConfig` GPU-enabled defaults with the existing runtime GPU-safe batch cap (`256`) so presets no longer advertise larger `nBatch`/`nUbatch` values than the planner can execute.
- Reused a shared `GPU_SAFE_BATCH_CAP` constant in both profile construction and planner enforcement to avoid future drift.
- Updated thermal adaptive batch adjustments to keep GPU-active configs at or below the same cap.

Caveats:
- CPU-only behavior is intentionally unchanged (`BALANCED=512`, `FAST=768` batch defaults).
- Planner-side GPU batch enforcement is still retained as a defensive clamp for recommended-config overrides and downstream mutations.
