# Phase 1 MVP Plan

Last updated: 2026-03-04

## Objective

Ship a usable, privacy-first offline assistant MVP with:

1. productionized runtime reliability
2. user-facing chat-first Android UX
3. evidence-backed beta go/no-go decision

Execution tracker:

- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/roadmap/next-steps-execution-plan.md`
- `docs/operations/execution-board.md`
- `docs/testing/test-strategy.md`

## Priority Backlog (Current)

1. Close WP-07 Stage-6 packet (final signoff stamps)
2. Close WP-11 user-facing MVP UX package
3. Execute UI acceptance suite (`UI-01`..`UI-10`) on device lane
4. Finalize external beta release packet after dual-track gate closure

## Completed Foundations

1. Runtime baseline (`llama.cpp`) and artifact benchmark reliability
2. Routing/policy/diagnostics hardening
3. Tool runtime safety productionization
4. Memory + image productionization
5. Marketing/product lock pass artifacts

## Active Milestones

### M4-A (Now): Reliability Gate Closure

- WP-07 Stage-6 closure
- final risk/register + signoff stamps

### M4-B (Now): User-Facing UX Closure

- WP-11 Compose app UX closure
- UI acceptance evidence and gate signoff

### M4-C (Release): External Beta Decision

- requires M4-A + M4-B complete

## Go/No-Go Rule (Current)

Go only if all are true:

1. Stage-6 technical hardening is closed (`WP-07 Done`)
2. User-facing MVP UX package is closed (`WP-11 Done`)
3. UI acceptance suite (`UI-01`..`UI-10`) is PASS
4. Product, QA, Engineering all signed go/no-go packet

No-Go if any are true:

1. Sustained thermal regressions remain unresolved
2. Frequent OOM/ANR/startup instability persists
3. Privacy/policy controls are not enforceable in implemented flows
4. Core user-facing chat/image/tool/session flows fail acceptance suite

## MVP Completion Definition

MVP is complete when all are true:

1. Stage 1-6 exit criteria in `docs/roadmap/next-steps-execution-plan.md` are satisfied.
2. Required evidence artifacts are present for benchmark, soak, and UI acceptance runs.
3. Cross-functional go/no-go packet is approved.
