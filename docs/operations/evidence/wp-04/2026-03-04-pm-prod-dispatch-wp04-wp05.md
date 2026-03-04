# PM/Prod Dispatch Packet - WP-04 and WP-05 (Owner Scope + Acceptance + Dependencies)

Date: 2026-03-04  
Owner: PM/Product Engineering  
Ticket source: `docs/operations/execution-board.md` Ready item (`PM/Prod dispatch for WP-04/WP-05 owner-level scope, acceptance criteria, and dependencies`)

## Reconciled Package State

1. WP-04: `In Progress` (ENG-05 implementation landed; QA-03 rerun pending final merge set validation).
2. WP-05: `Done` (ENG-06 package closeout captured; QA-04 baseline captured, rerun on merge-set deltas only).

## Dispatch Objective

Publish explicit owner-level execution packets for WP-04/WP-05 with measurable acceptance and clear dependency gates so downstream teams can progress without scope ambiguity.

## Owner Packet A - Runtime Eng + QA/Security (WP-04 Closeout)

### Scope

1. Finalize routing/policy/diagnostics hardening on merged code path.
2. Run QA-03 rerun on latest WP-04 merge set.
3. Verify diagnostics output remains privacy-safe (no prompt/response/tool payload leakage).

### Non-goals

1. WP-06 memory/image productionization.
2. WP-07 soak/go-no-go packaging.

### Acceptance Criteria

1. Routing boundary tests pass for battery/thermal/RAM/task thresholds.
2. Policy enforcement checks pass for runtime action flow (routing, inference, memory, tool, diagnostics export).
3. Diagnostics redaction checks pass and no sensitive payload appears in exported diagnostics evidence.
4. Board + role playbook status are synced with evidence note links.

### Required Evidence

1. Engineering evidence: `docs/operations/evidence/wp-04/YYYY-MM-DD-eng-05.md` (already landed for implementation scope).
2. QA rerun evidence: `docs/operations/evidence/wp-04/YYYY-MM-DD-qa-03.md` on final merge set.

### Dependency Gate

1. WP-04 must be marked `Done` before WP-06 progression.

## Owner Packet B - Platform Eng + QA/Security (WP-05 Stability Hold)

### Scope

1. Keep WP-05 `Done` package integrity stable while parallel merges continue.
2. Execute QA-04 rerun only if new WP-05-affecting deltas land.

### Non-goals

1. New tool feature surface expansion outside allowlist/schema contracts.

### Acceptance Criteria

1. Tool schema/allowlist/adversarial suite remains green.
2. No regression of deterministic tool error contracts in CI-safe runs.
3. Any new deltas have updated evidence and board sync.

### Required Evidence

1. Existing closeout packet: `docs/operations/evidence/wp-05/2026-03-04-eng-06-closeout.md`.
2. QA rerun packet when needed: `docs/operations/evidence/wp-05/YYYY-MM-DD-qa-04.md`.

### Dependency Gate

1. WP-05 `Done` is prerequisite for WP-07.

## Cross-Package Coordination Rules

1. Do not mark WP-04 `Done` until QA-03 rerun reflects final merged WP-04 deltas.
2. Keep WP-05 as `Done` unless regression evidence reopens package status.
3. Use deterministic evidence paths:
   - raw artifacts: `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...`
   - human evidence: `docs/operations/evidence/wp-xx/...`

## Commands Run for Dispatch Validation

1. `./gradlew --no-daemon :packages:core-domain:test :packages:inference-adapters:test :packages:tool-runtime:test`
   - Outcome: PASS (`BUILD SUCCESSFUL`)

## Dispatch Result

Ready dispatch item is satisfied: owner-level WP-04/WP-05 scope, acceptance criteria, and dependency gates are now explicitly documented and linked for execution tracking.
