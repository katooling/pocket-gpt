# Engineering Playbook

Last updated: 2026-03-05

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
- [x] ENG-07 completed: shared memory backend + pruning
- [x] ENG-08 completed: runtime image path hardening
- [x] ENG-10 completed: WP-11 Compose MVP UI implementation foundation
- [x] ENG-11A completed: native-runtime truth gate for startup/closure-path checks
- [x] ENG-12 completed: side-load/manual-internal model distribution with strict verify-before-load hard-block policy
- [x] ENG-13 completed: native JNI runtime proof and Samsung 0.8B/2B evidence packet
- [x] ENG-14 completed: Android-native runtime memory backend migration
- [x] ENG-15 completed: local tool store integration for notes/search/reminders
- [x] ENG-16 completed: production runtime-backed image path wiring
- [x] ENG-17 completed: platform network policy wiring + regression checks
- [x] ENG-18 completed: UI accessibility + deterministic error-state hardening for beta rollout
- [x] ENG-13 support completed: Stage-2 runtime evidence integrity validator + runbook wiring

## What Is In Progress

- [ ] WP-09 rollout support and stabilization backlog
- [ ] DX-01 layered test profiles + Stage-2 quick/closure efficiency rollout
- [ ] DX-02 provider-style caching rollout (native prefix/KV reuse + runtime cache telemetry)

## Lead Eng Dispatch (Now)

1. Support WP-09 rollout readiness and stabilization backlog.
2. Keep regression/lane stability for external beta.
3. Track post-WP12 performance optimization follow-ups from ENG-13 threshold report.

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| ENG-01 | Implement Gradle wrapper + stable local build command | Done | WP-01 | Eng Lead | `docs/operations/evidence/wp-01/2026-03-03-eng-01.md` |
| ENG-02 | Add CI for module tests and app tests | Done | ENG-01 | Eng Platform | `docs/operations/evidence/wp-01/2026-03-03-eng-02.md` |
| ENG-03 | Integrate real Android `llama.cpp` runtime bridge | Done | WP-01 complete | Runtime Eng | `docs/operations/evidence/wp-02/2026-03-03-eng-03-device-pass-02.md` |
| ENG-04 | Artifact manifest/checksum/version lifecycle | Done | ENG-03 | Runtime Eng | `docs/operations/evidence/wp-03/2026-03-03-eng-04-closeout.md` |
| ENG-05 | Routing + policy hardening with boundary tests | Done | ENG-04 | Runtime Eng | `docs/operations/evidence/wp-04/2026-03-04-eng-05.md` |
| ENG-06 | Tool runtime strict schema validation | Done | ENG-04 | Platform Eng | `docs/operations/evidence/wp-05/2026-03-04-eng-06-closeout.md` |
| ENG-07 | Shared memory backend + pruning | Done | ENG-05 | Core Eng | `docs/operations/evidence/wp-06/2026-03-04-eng-07-closeout.md` |
| ENG-08 | Image path production hardening | Done | ENG-07 | Runtime Eng | `docs/operations/evidence/wp-06/2026-03-04-eng-08.md` |
| ENG-10 | WP-11 Compose chat UX + runtime facade + session persistence + advanced controls | Done | WP-06 | Android Runtime Eng | `docs/operations/evidence/wp-11/2026-03-04-eng-wp11-ui-foundation.md`, `docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md` |
| ENG-WP07-SIGNOFF | WP-07 Stage-6 final engineering signoff for go/no-go packet | Done | QA-06, ENG-WP07-S6 | Engineering Lead | `docs/operations/evidence/wp-07/2026-03-04-prod-03-final-signoff.md` |
| ENG-11A | Native-runtime truth gate (block closure startup checks on `ADB_FALLBACK`) | Done | WP-11 | Runtime Eng | `docs/operations/evidence/wp-12/2026-03-04-eng-11-runtime-truth-gate.md` |
| ENG-12 | Side-load model distribution + strict manifest/SHA/provenance hard-block policy | Done | ENG-11A, Product decision note | Runtime Eng | `docs/operations/evidence/wp-12/2026-03-04-eng-12-model-distribution-implementation.md`, `docs/operations/evidence/wp-12/2026-03-04-prod-eng-12-model-distribution-decision.md` |
| ENG-13 | Native JNI runtime proof on Samsung + perf/memory characterization (0.8B/2B) | Done | ENG-11A | Runtime Eng, QA | `docs/operations/evidence/wp-12/2026-03-04-eng-13-native-runtime-proof.md`, `docs/testing/stage-2-benchmark-runbook.md` |
| ENG-13-SUPPORT | Stage-2 closure artifact integrity validator (`NATIVE_JNI`/non-placeholder guard) | Done | ENG-11A | Eng Platform | `scripts/benchmarks/validate_stage2_runtime_evidence.py`, `docs/testing/stage-2-benchmark-runbook.md` |
| ENG-14 | Replace JVM-JDBC memory path with shared file-backed memory backend | Done | ENG-12 artifact-path contract stable | Core Eng | `docs/operations/evidence/wp-12/2026-03-04-eng-14-android-native-memory.md` |
| ENG-15 | Replace placeholder notes/search/reminder responses with real local stores | Done | ENG-12 artifact-path contract stable | Platform Eng | `docs/operations/evidence/wp-12/2026-03-04-eng-15-tool-store-integration.md` |
| ENG-16 | Replace smoke image adapter with production multimodal runtime path | Done | ENG-12 artifact-path contract stable | Runtime Eng | `docs/operations/evidence/wp-12/2026-03-04-eng-16-image-runtime-path.md` |
| ENG-17 | Wire policy module to Android platform network behavior + regressions | Done | ENG-12..ENG-16 integration available | Security Eng | `docs/operations/evidence/wp-12/2026-03-04-eng-17-network-policy-wiring.md` |
| ENG-18 | UI accessibility + error-state hardening for beta rollout | Done | WP-11 | Android Eng | `docs/operations/evidence/wp-09/2026-03-04-eng-18-ui-accessibility-error-hardening.md`, `docs/testing/test-strategy.md` |
| DX-01 | Layered test profiles + Stage-2 quick/closure efficiency automation | In Progress | WP-12 closeout | Eng Platform + Runtime | `docs/testing/test-lane-profiles-and-selection.md`, `scripts/dev/README.md` |
| DX-02 | Provider-style caching rollout (native prefix reuse + runtime response cache + Stage-2 cache telemetry) | In Progress | DX-01 baseline | Runtime Eng + Platform Eng | `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt`, `apps/mobile-android/src/main/cpp/pocket_llama.cpp`, `docs/testing/stage-2-benchmark-runbook.md` |
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
