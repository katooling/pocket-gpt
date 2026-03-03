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
- [x] ENG-03 completed: real Android runtime bridge path integrated with physical-device 10-run evidence (`docs/operations/evidence/wp-02/2026-03-03-eng-03-device-pass-02.md`)
- [x] ENG-OPS completed: engineering foundations simplification (strict PR gates, canonical docs, benchmark automation wrapper, Android app + host lane split) (`docs/operations/evidence/wp-03/2026-03-03-eng-ops-foundations.md`)
- [x] ENG-04 completed: artifact-manifest startup validation enforced, placeholder checksum removed from active Stage-2 path, QA handoff unblocked (`docs/operations/evidence/wp-03/2026-03-03-eng-04-closeout.md`)

## What Is In Progress

- [ ] ENG-06 Tool runtime strict schema validation hardening (parallel CI-first scope; device-independent)

## Lead Eng Dispatch (Now)

1. Run in parallel:
   - ENG-06 package-close prep (keep tests/evidence current; package remains not-Done until WP-03 and package acceptance criteria are met)
2. Handoff trigger:
   - ENG-04 closeout evidence landed; QA is unblocked to execute final QA-02 closure refresh run

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| ENG-01 | Implement Gradle wrapper + stable local build command | Done | WP-01 | Eng Lead | `docs/operations/evidence/wp-01/2026-03-03-eng-01.md` |
| ENG-02 | Add CI for module tests and app tests | Done | ENG-01 | Eng Platform | `docs/operations/evidence/wp-01/2026-03-03-eng-02.md` |
| ENG-03 | Integrate real Android `llama.cpp` runtime bridge | Done | WP-01 complete | Runtime Eng | `docs/operations/evidence/wp-02/2026-03-03-eng-03-device-pass-02.md` |
| ENG-04 | Artifact manifest/checksum/version lifecycle | Done | ENG-03 | Runtime Eng | `docs/operations/evidence/wp-03/2026-03-03-eng-04-closeout.md` |
| ENG-05 | Routing + policy hardening with boundary tests | Backlog | ENG-04 | Runtime Eng | `docs/security/privacy-model.md` |
| ENG-06 | Tool runtime strict schema validation | In Progress | ENG-04 | Platform Eng | `docs/product/feature-catalog.md` |
| ENG-07 | SQLite memory backend + pruning | Backlog | ENG-05 | Core Eng | `docs/roadmap/mvp-implementation-tracker.md` |
| ENG-08 | Image path production hardening | Backlog | ENG-07 | Runtime Eng | `docs/feasibility/benchmark-protocol.md` |
| ENG-09 | STT/TTS technical spikes (post-MVP) | Backlog | WP-07 | Runtime Eng | `docs/roadmap/product-roadmap.md` |
| ENG-OPS | Engineering foundations simplification (governance + docs + automation + Android module alignment) | Done | ENG-03 | Eng Platform | `docs/operations/evidence/wp-03/2026-03-03-eng-ops-foundations.md` |

## Engineering Definition of Done

1. Tests added/updated for changed behavior.
2. No policy/security regressions introduced.
3. Required benchmark or runtime evidence attached for stage tasks.
4. `execution-board.md` and this file status updated.

## Evidence Log

- 2026-03-03: ENG-01 evidence captured in `docs/operations/evidence/wp-01/2026-03-03-eng-01.md`
- 2026-03-03: ENG-02 evidence captured in `docs/operations/evidence/wp-01/2026-03-03-eng-02.md`
- 2026-03-03: ENG-03 runtime bridge integration evidence captured in `docs/operations/evidence/wp-02/2026-03-03-eng-03.md`
- 2026-03-03: ENG-03 automation foundation update captured in `docs/operations/evidence/wp-02/2026-03-03-eng-03-automation-foundation.md`
- 2026-03-03: ENG-03 device pass 01 captured in `docs/operations/evidence/wp-02/2026-03-03-eng-03-device-pass-01.md`
- 2026-03-03: ENG-03 device pass 02 captured in `docs/operations/evidence/wp-02/2026-03-03-eng-03-device-pass-02.md`
- 2026-03-03: ENG-04 artifact lifecycle evidence (parallel in progress) captured in `docs/operations/evidence/wp-03/2026-03-03-eng-04.md`
- 2026-03-03: ENG-04 closeout evidence (active Stage-2 startup path enforces checksum metadata and removes placeholder ambiguity) captured in `docs/operations/evidence/wp-03/2026-03-03-eng-04-closeout.md`
- 2026-03-03: ENG-06 tool schema hardening evidence (parallel in progress) captured in `docs/operations/evidence/wp-05/2026-03-03-eng-06.md`
- 2026-03-03: ENG-OPS engineering foundations simplification captured in `docs/operations/evidence/wp-03/2026-03-03-eng-ops-foundations.md`
- 2026-03-03: ENG devctl DX consolidation (config-driven orchestrator + Maestro/Espresso lanes) captured in `docs/operations/evidence/wp-03/2026-03-03-eng-devctl-dx-consolidation.md`
- 2026-03-03: Platform governance hardening refresh with governance self-test coverage captured in `docs/operations/evidence/wp-03/2026-03-03-eng-platform-governance-refresh.md`

## Engineering References

- `docs/operations/execution-board.md`
- `docs/roadmap/next-steps-execution-plan.md`
- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/testing/test-strategy.md`
- `docs/architecture/modular-monolith.md`
