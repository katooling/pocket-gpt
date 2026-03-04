# Engineering Playbook

Last updated: 2026-03-04

## Mission

Deliver reliable local runtime capability and user-facing MVP experience while preserving modular boundaries and privacy/safety guarantees.

## Where To Start

1. Check `docs/operations/execution-board.md`.
2. Pull engineering-owned tasks in `Ready`/`In Progress` state.
3. Confirm prerequisites are complete.
4. Update this playbook status table when you start/finish.

## What Is Done

- [x] Core module interfaces scaffolded (`Conversation`, `Inference`, `Routing`, `Tool`, `Memory`, `Policy`, `Observability`)
- [x] Android-first scaffolding and benchmark scripts established
- [x] ENG-01 completed: Gradle wrapper + one-command verification baseline
- [x] ENG-02 completed: CI workflow baseline
- [x] ENG-03 completed: real Android runtime bridge + physical-device evidence
- [x] ENG-04 completed: artifact-manifest startup validation + checksum lifecycle gate
- [x] ENG-05 completed: routing/policy/diagnostics hardening
- [x] ENG-06 completed: strict schema-safe tool runtime productionization
- [x] ENG-07 completed: SQLite memory backend + pruning
- [x] ENG-08 completed: runtime image path hardening
- [x] ENG-10 completed: WP-11 Compose MVP UI implementation foundation
- [x] ENG-11A completed: native-runtime truth gate for startup/closure-path checks

## What Is In Progress

- [ ] WP-12 backend production runtime closure
- [ ] ENG-11 native-runtime truth gate execution (phase A complete; follow-on rollout checks in progress)

## Lead Eng Dispatch (Now)

1. Support WP-09 rollout readiness and stabilization backlog.
2. Execute WP-12 ENG-12..ENG-17 sequence: model distribution, native runtime proof, Android-native data plane, and policy wiring.
3. Keep regression/lane stability for external beta.

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| ENG-01 | Implement Gradle wrapper + stable local build command | Done | WP-01 | Eng Lead | `docs/operations/evidence/wp-01/2026-03-03-eng-01.md` |
| ENG-02 | Add CI for module tests and app tests | Done | ENG-01 | Eng Platform | `docs/operations/evidence/wp-01/2026-03-03-eng-02.md` |
| ENG-03 | Integrate real Android `llama.cpp` runtime bridge | Done | WP-01 complete | Runtime Eng | `docs/operations/evidence/wp-02/2026-03-03-eng-03-device-pass-02.md` |
| ENG-04 | Artifact manifest/checksum/version lifecycle | Done | ENG-03 | Runtime Eng | `docs/operations/evidence/wp-03/2026-03-03-eng-04-closeout.md` |
| ENG-05 | Routing + policy hardening with boundary tests | Done | ENG-04 | Runtime Eng | `docs/operations/evidence/wp-04/2026-03-04-eng-05.md` |
| ENG-06 | Tool runtime strict schema validation | Done | ENG-04 | Platform Eng | `docs/operations/evidence/wp-05/2026-03-04-eng-06-closeout.md` |
| ENG-07 | SQLite memory backend + pruning | Done | ENG-05 | Core Eng | `docs/operations/evidence/wp-06/2026-03-04-eng-07-closeout.md` |
| ENG-08 | Image path production hardening | Done | ENG-07 | Runtime Eng | `docs/operations/evidence/wp-06/2026-03-04-eng-08.md` |
| ENG-10 | WP-11 Compose chat UX + runtime façade + session persistence + advanced controls | Done | WP-06 | Android Runtime Eng | `docs/operations/evidence/wp-11/2026-03-04-eng-wp11-ui-foundation.md`, `docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md` |
| ENG-WP07-SIGNOFF | WP-07 Stage-6 final engineering signoff for go/no-go packet | Done | QA-06, ENG-WP07-S6 | Engineering Lead | `docs/operations/evidence/wp-07/2026-03-04-prod-03-final-signoff.md` |
| ENG-11A | Native-runtime truth gate (block closure startup checks on `ADB_FALLBACK`) | Done | WP-11 | Runtime Eng | `docs/operations/evidence/wp-12/2026-03-04-eng-11-runtime-truth-gate.md` |
| ENG-12 | Model distribution implementation (bundle/download/sideload decision + checksum provenance) | Ready | ENG-11A | Runtime Eng | `docs/operations/execution-board.md` |
| ENG-13 | Real Samsung runtime performance + memory characterization (0.8B/2B) | Ready | ENG-11A | Runtime Eng, QA | `docs/testing/stage-2-benchmark-runbook.md` |
| ENG-14 | Android-native SQLite backend for runtime memory path | Ready | ENG-11A | Core Eng | `packages/memory` |
| ENG-15 | Real data-store integration for notes/search/reminder tools | Ready | ENG-11A | Platform Eng | `packages/tool-runtime` |
| ENG-16 | Real multimodal image runtime path integration | Ready | ENG-11A | Runtime Eng | `packages/inference-adapters` |
| ENG-17 | Platform network policy enforcement wiring + regressions | Ready | ENG-11A | Security Eng | `docs/security/privacy-model.md` |
| ENG-18 | UI accessibility + error-state hardening for beta rollout | Ready | WP-11 | Android Eng | `docs/operations/ui-ux-handoff-ticket-pack.md`, `docs/testing/test-strategy.md` |
| ENG-09 | STT/TTS technical spikes (post-MVP) | Backlog | WP-07 | Runtime Eng | `docs/roadmap/product-roadmap.md` |
| ENG-OPS | Engineering foundations simplification (governance + docs + automation + Android module alignment) | Done | ENG-03 | Eng Platform | `docs/operations/evidence/wp-03/2026-03-03-eng-ops-foundations.md` |

## Engineering Definition of Done

1. Tests added/updated for changed behavior.
2. No policy/security regressions introduced.
3. Required benchmark/runtime/UI evidence attached for stage tasks.
4. `execution-board.md` and this file status updated.

## Engineering References

- `docs/operations/execution-board.md`
- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/testing/test-strategy.md`
- `docs/testing/android-dx-and-test-playbook.md`
- `docs/architecture/modular-monolith.md`
