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
- [x] ENG-01 completed: Gradle wrapper + one-command verification baseline (`docs/operations/evidence/wp-01/2026-03-03-eng-01.md`)
- [x] ENG-02 completed: CI workflow for clean/test + test artifact upload (`docs/operations/evidence/wp-01/2026-03-03-eng-02.md`)

## What Is In Progress

- [ ] ENG-03 Integrate real Android `llama.cpp` runtime bridge (awaiting physical-device validation evidence)

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| ENG-01 | Implement Gradle wrapper + stable local build command | Done | WP-01 | Eng Lead | `docs/operations/evidence/wp-01/2026-03-03-eng-01.md` |
| ENG-02 | Add CI for module tests and app tests | Done | ENG-01 | Eng Platform | `docs/operations/evidence/wp-01/2026-03-03-eng-02.md` |
| ENG-03 | Integrate real Android `llama.cpp` runtime bridge | In Progress | WP-01 complete | Runtime Eng | `docs/roadmap/next-steps-execution-plan.md` |
| ENG-04 | Artifact manifest/checksum/version lifecycle | Backlog | ENG-03 | Runtime Eng | `docs/feasibility/benchmark-protocol.md` |
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

## Evidence Log

- 2026-03-03: ENG-01 evidence captured in `docs/operations/evidence/wp-01/2026-03-03-eng-01.md`
- 2026-03-03: ENG-02 evidence captured in `docs/operations/evidence/wp-01/2026-03-03-eng-02.md`
- 2026-03-03: ENG-03 runtime bridge integration evidence (in progress) captured in `docs/operations/evidence/wp-02/2026-03-03-eng-03.md`

## Engineering References

- `docs/operations/execution-board.md`
- `docs/roadmap/next-steps-execution-plan.md`
- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/testing/test-strategy.md`
- `docs/architecture/modular-monolith.md`
