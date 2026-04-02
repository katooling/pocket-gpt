# Wave-1 Operating Rules

## 1. Ownership Boundaries

- Native Runtime Lead owns mechanism correctness and backend/runtime behavior.
  - Examples: backend compatibility logic, cache format/restore checks, native diagnostics.
- Runtime Policy Lead owns selection, defaulting, and tuning logic above the runtime.
  - Examples: profile tables, speculative enablement policy, tuning-key schema.
- Benchmarking and Validation Lead owns proof standards and benchmark/gate policy.
  - Examples: stage2 trigger rules, evidence floors, device matrix criteria.
- Comparative Systems and External Research Leads are input authorities.
  - They inform decisions and validation order; they do not override Pocket GPT code ownership.

Conflict rule:
- Mechanism dispute -> Native Runtime Lead final.
- Selection/tuning dispute -> Runtime Policy Lead final.
- Evidence sufficiency dispute -> Benchmarking and Validation Lead final.
- Cross-boundary deadlock -> Senior Lead final.

## 2. Handoff And Knowledge-Sharing Rules

1. Every completed task must publish:
   - applied config fingerprint,
   - artifacts/log links,
   - fallback behavior summary,
   - explicit risks not closed.
2. Handoffs are valid only when the receiver lead acknowledges:
   - what changed,
   - what remains open,
   - how to validate/regress it.
3. No direct engineer-to-engineer cross-team handoff without lead approval.
4. Comparator or external evidence must be mapped to a Pocket GPT code path and validation step before adoption.
5. Unresolved decisions or ownership conflicts go to `../logs/decision-log.md`.

## 3. Acceptance Gates For Wave 1

A wave-1 task is accepted only if all gates pass:

1. Correctness gate
   - expected behavior and fallback behavior are both verified for impacted paths.
2. Evidence gate
   - benchmark evidence meets the stage2 quality floor (including raw per-run data).
3. Diagnostics gate
   - logs/telemetry are sufficient to explain wins, regressions, and fallback decisions.
4. Ownership gate
   - code owner, measurement owner, and rollback owner are explicit.
5. Regression gate
   - optimization-risk changes satisfy required `stage2 quick` trigger policy.

Primary gate references:
- `../findings/benchmarking-validation/lead-summary.md`
- `../findings/benchmarking-validation/engineer-acceptance-gates.md`
- `../master-plan.md` (Validation Gates section)

## 4. Research-Only Status (Not Wave-1 Delivery Scope)

The following stay research-only until post-wave-1 evidence and governance are complete:

- KV-cache quantization below `Q8_0` (including KIVI-like low-bit variants).
- Medusa-style or model-architecture-changing speculative alternatives.
- Broad per-tensor/per-layer aggressive GGUF quantization strategies.
- Any Hexagon flash-attention expansion beyond qualified experimental gating.
- Comparator-inspired server-first cache/concurrency patterns not proven for mobile constraints.

Source basis:
- `../findings/external-research/lead-summary.md`
- `../findings/comparative-systems/lead-summary.md`
- `../master-plan.md` (Phase 3 advanced features)
