# 2026-03-27 Session Cache Identity Wave 2

## Direction
- Session cache identity moves to a version-aware key shape so cache reuse is bound to the effective model version, not just model ID/path fingerprints.
- Restore eligibility is gated by strict sidecar metadata checks; restore is allowed only when required sidecar fields are present and match the active load contract.
- Runtime load path automatically forwards `modelVersion` and `strictGpuOffload` through planning and bridge boundaries so cache identity and load behavior are derived from one source of truth.

## Expected Runtime Behavior
- Cache hits should survive normal session continuity while avoiding cross-version reuse when model artifacts or explicit version tags change.
- Restore attempts without valid sidecar metadata should fail closed (cold load path) instead of soft-matching partially known state.
- Callers should not need manual wiring for `modelVersion`/`strictGpuOffload`; defaults and resolved options should propagate automatically.

## Residual Risks
- Sidecar/schema drift across app versions can still increase cold-load frequency until migration and backfill paths are fully stabilized.
- If upstream model registries provide missing or inconsistent version labels, identity precision degrades and more traffic falls back to conservative misses.
- Strict restore gating can surface latency regressions on first-run or post-upgrade sessions; observability should track miss reasons to separate policy misses from true incompatibility.
- `strictGpuOffload` forwarding reduces ambiguity but does not remove device/runtime variability; GPU qualification failures can still force CPU fallback even with matching identity metadata.
