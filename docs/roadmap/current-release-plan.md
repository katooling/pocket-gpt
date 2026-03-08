# Current Release Plan

Last updated: 2026-03-08

This is the single current-release planning document. It replaces:

- `docs/roadmap/phase-1-mvp-plan.md`
- `docs/roadmap/next-steps-execution-plan.md`
- `docs/operations/rebased-release-plan-2026-03-05.md`

Mutable status is tracked only in `docs/operations/execution-board.md`.

## Objective

Ship a usable privacy-first Android MVP under soft-gate pilot policy with complete UX-quality and promotion evidence.

## Baseline

1. Foundational package work through WP-12 is closed; WP-09 and WP-13 drive current release risk.
2. Build policy is single-build with download manager enabled by default.
3. Promotion beyond pilot remains blocked until required WP-13 usability and launch-gate evidence is complete.

## Release phases

### Phase 1: Stabilize execution contracts

1. Keep docs, board, and ticket specs synchronized to one source-of-truth model.
2. Keep lane reliability stable for `android-instrumented`, `maestro`, and `journey`.
3. Ensure runtime timeout/cancel and send-capture contracts remain deterministic.
4. 2026-03-08 update: session-ID collision hardening, provenance strict-mode wiring, and chat-flow split landed with unit-test validation.

### Phase 2: Close usability and operations gaps

1. Complete moderated usability packet and required QA lane evidence.
2. Complete timeout/cancel UX and runtime contract hardening tickets.
3. Complete claim-safety and privacy parity ticket set for launch review.

### Phase 3: Promotion decision

1. Run launch-gate matrix review from ticket specs and current evidence.
2. Publish promote/hold decision memo with explicit constraints and follow-ups.
3. Keep policy updates in board + ticket specs only.

## Required pre-promotion signals

1. Unit and required device lanes pass.
2. Latest lane pass IDs exist for `android-instrumented`, `maestro`, and `journey`.
3. WP-13 usability packet has measured values (no placeholders).
4. No open high-severity UX blockers in required launch workflows.
5. Claim rows in launch-gate matrix map to evidence IDs and remain privacy-compliant.

## Interfaces

- Active status board: `docs/operations/execution-board.md`
- Active ticket specs: `docs/operations/tickets/`
- Evidence inventory and retention policy: `docs/operations/evidence/index.md`
- Launch policy + decision artifacts: `docs/operations/tickets/prod-09-soft-gate-pilot-policy.md`, `docs/operations/tickets/prod-10-launch-gate-matrix.md`
