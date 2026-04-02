# Team Topology (Wave 1)

## Command Structure

- Senior Lead
  - Owns wave-1 scope control, cross-lead conflict resolution, and final acceptance signoff.
- Five Leads
  - Native Runtime Lead
  - Runtime Policy Lead
  - Benchmarking and Validation Lead
  - Comparative Systems Lead
  - External Research Lead
- Engineers
  - Assigned to bounded implementation or validation tasks under a single lead at a time.

This keeps the research-phase org shape from `../org-structure.md` and switches it to delivery mode.

## Lead Charters In Execution Mode

- Native Runtime Lead
  - Owns mechanism correctness and native/runtime hardening tasks from wave 1.
- Runtime Policy Lead
  - Owns policy tables, tuning-key scope, and runtime selection logic changes.
- Benchmarking and Validation Lead
  - Owns evidence-floor hardening, stage2 trigger policy, and acceptance proof.
- Comparative Systems Lead
  - Owns comparator-backed design review inputs and parity spot-check framing.
- External Research Lead
  - Owns upstream compatibility checks and evidence for risk-sensitive choices.

## Engineer Pod Pattern

- Pod A: Native correctness hardening
  - Supports Native Runtime Lead.
- Pod B: Policy normalization and tuning control plane
  - Supports Runtime Policy Lead.
- Pod C: Benchmark and gate hardening
  - Supports Benchmarking and Validation Lead.
- Pod D: Evidence support and comparator validation
  - Supports Comparative Systems + External Research Leads (input-only to wave 1 build tasks).

## Escalation Path

1. Engineer escalates blocker to owning lead with concrete evidence.
2. Lead resolves within charter; if cross-boundary, escalate to Senior Lead.
3. Senior Lead makes final ownership call using:
   - mechanism issues -> Native Runtime
   - selection/tuning issues -> Runtime Policy
   - proof/gate issues -> Benchmarking and Validation
