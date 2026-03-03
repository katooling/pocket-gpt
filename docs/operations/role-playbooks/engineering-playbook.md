# Engineering Playbook

Last updated: 2026-03-03

## Mission

Deliver reliable local runtime capability and core feature implementation for MVP while preserving modular boundaries and privacy/safety guarantees.

## Where To Start

1. Check `docs/operations/execution-board.md`.
2. Pull engineering-owned tasks in `Ready` state.
3. Confirm prerequisites are complete.
4. Update this playbook status table when you start/finish.

## What Is Done

- [x] Core module interfaces scaffolded (`Conversation`, `Inference`, `Routing`, `Tool`, `Memory`, `Policy`, `Observability`)
- [x] Android-first scaffolding and benchmark scripts established
- [x] Foundational docs and architecture ADRs complete

## What Is In Progress

- [ ] No active engineering task currently marked in-progress

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| ENG-01 | Implement Gradle wrapper + stable local build command | Ready | WP-01 | Eng Lead | `docs/roadmap/next-steps-execution-plan.md` |
| ENG-02 | Add CI for module tests and app tests | Ready | ENG-01 | Eng Platform | `docs/testing/test-strategy.md` |
| ENG-03 | Integrate real Android `llama.cpp` runtime bridge | Ready | ENG-01 | Runtime Eng | `docs/roadmap/next-steps-execution-plan.md` |
| ENG-04 | Artifact manifest/checksum/version lifecycle | Ready | ENG-03 | Runtime Eng | `docs/feasibility/benchmark-protocol.md` |
| ENG-05 | Routing + policy hardening with boundary tests | Backlog | ENG-04 | Runtime Eng | `docs/security/privacy-model.md` |
| ENG-06 | Tool runtime strict schema validation | Backlog | ENG-04 | Platform Eng | `docs/product/feature-catalog.md` |
| ENG-07 | SQLite memory backend + pruning | Backlog | ENG-05 | Core Eng | `docs/roadmap/mvp-implementation-tracker.md` |
| ENG-08 | Image path production hardening | Backlog | ENG-07 | Runtime Eng | `docs/feasibility/benchmark-protocol.md` |
| ENG-09 | STT/TTS technical spikes (post-MVP) | Backlog | WP-07 | Runtime Eng | `docs/roadmap/product-roadmap.md` |

## Engineering Definition of Done

1. Tests added/updated for changed behavior.
2. No policy/security regressions introduced.
3. Required benchmark or runtime evidence attached for stage tasks.
4. `execution-board.md` and this file status updated.

## Engineering References

- `docs/operations/execution-board.md`
- `docs/roadmap/next-steps-execution-plan.md`
- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/testing/test-strategy.md`
- `docs/architecture/modular-monolith.md`
