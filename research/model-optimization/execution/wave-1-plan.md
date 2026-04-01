# Wave-1 Ordered Task Plan

Scope source: phase-1 and phase-2 immediate priorities in `../master-plan.md` plus lead findings.

## Task Sequence

| Order | Task | Primary Owner | Supporting Owners | Depends On | Done When |
|---|---|---|---|---|---|
| W1-01 | Replace filename-based quantization/OpenCL gating with metadata-driven compatibility | Native Runtime Lead | Runtime Policy Lead | none | Quant/backend decisions no longer rely on filename heuristics |
| W1-02 | Add session-cache compatibility metadata and reject mismatches on restore | Native Runtime Lead | Benchmarking and Validation Lead | W1-01 | Cache restore fails safely on model/backend/config incompatibility |
| W1-03 | Normalize effective `nBatch`/`nUbatch` behavior across profile defaults, planner clamps, and runtime dispatch | Runtime Policy Lead | Native Runtime Lead | none | One canonical effective policy, with tests and documented limits |
| W1-04 | Replace speculative defaulting with verified draft-model compatibility matrix | Runtime Policy Lead | Native Runtime Lead, External Research Lead | W1-03 | Speculative enablement is pair-based and explicitly gated |
| W1-05 | Expand tuning key dimensions (model version, quant class, context bucket, backend identity) | Runtime Policy Lead | Benchmarking and Validation Lead | W1-03 | Recommendations are keyed by material runtime differences |
| W1-06 | Expose backend-specific failure reasons and tuning-relevant telemetry to policy layer | Native Runtime Lead | Runtime Policy Lead | W1-01 | Tuning loop can distinguish capability failure vs resource failure |
| W1-07 | Add `stage2 quick` lane and risk-trigger policy for optimization-sensitive changes | Benchmarking and Validation Lead | Senior Lead | W1-03 | Risk-tagged/path-matched changes auto-require quick benchmark proof |
| W1-08 | Enforce evidence floors (min token budget, run count, raw per-run rows, unrealistic TPS rejection) | Benchmarking and Validation Lead | Runtime Policy Lead | W1-07 | Weak or underpowered benchmark runs are rejected |
| W1-09 | Run baseline matrix for `F16` vs `Q8_0` KV cache and backend-specific GPU-layer ceilings on representative devices | Benchmarking and Validation Lead | Native Runtime Lead, External Research Lead | W1-08 | Matrix artifacts published and used as tuning baselines |
| W1-10 | Senior-lead wave-1 closure: verify gates, owners, rollback notes, and unresolved research carryover | Senior Lead | All leads | W1-01..W1-09 | Wave-1 acceptance package signed with remaining research-only backlog |

## Dependency Notes

- W1-01 and W1-03 are the parallel starting tracks.
- W1-07/W1-08 must complete before any wave-1 performance claims are treated as releasable evidence.
- W1-09 is required for default-policy decisions touching KV type or GPU layer ceilings.

## Source Links

- `../master-plan.md`
- `../findings/native-runtime/lead-summary.md`
- `../findings/runtime-policy/lead-summary.md`
- `../findings/benchmarking-validation/lead-summary.md`
- `../findings/external-research/lead-summary.md`
