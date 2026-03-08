# Test Strategy (Canonical Playbook)

Last updated: 2026-03-08

## Source Of Truth

1. Command contract: `scripts/dev/README.md`
2. This document: quality strategy, lane policy, release gates
3. Task-focused runbooks: `docs/testing/runbooks.md`
4. Screenshot review workflow: `docs/testing/screenshot-regression-workflow.md`

## Quality Policy

1. Correctness is required; fast feedback does not replace deterministic validation.
2. Core user flows must be verifiable on both host and Android device lanes.
3. Product claims are publishable only when tests and evidence align.
4. Known quality debt requires owner, severity, and closure target.

## Core Flow Coverage Contract

| Core Flow | Minimum Automated Coverage | Lane/Evidence Expectation | Owner |
|---|---|---|---|
| Startup/readiness | Runtime startup checks + ViewModel mapping tests | `android-instrumented` smoke | Eng + QA |
| Send/streaming | stream event reducer + timeout/cancel tests | `journey` send-capture evidence | Eng + QA |
| Session continuity | persistence/session tests | weekly regression matrix evidence | Eng + QA |
| Image attach | runtime/image contract tests | maestro scenario coverage | Eng + QA |
| Tool safety contracts | tool-runtime schema tests + typed tool result mapping | local-tool evidence in QA matrix/usability packet | Eng + QA + Security |
| Privacy controls and redaction | diagnostics redaction tests + privacy UI checks | privacy claim-parity ticket evidence | Eng + QA + Security |
| Model setup recovery | provisioning/viewmodel tests | recovery evidence in WP-13 packet | Eng + QA + Product |

## Lane Policy

1. `bash scripts/dev/test.sh fast` for fast changed-file confidence.
2. `bash scripts/dev/test.sh merge` for merge-gate safety net.
3. `python3 tools/devctl/main.py lane android-instrumented` for Android smoke.
4. `python3 tools/devctl/main.py lane maestro` for E2E app workflows.
5. `python3 tools/devctl/main.py lane journey` for strict send/runtime journey evidence.
6. `python3 tools/devctl/main.py lane screenshot-pack` for UI screenshot contract.
7. Stage-2 runtime closure lanes remain physical-device signoff lanes.

## Release Gates

Required for promotion decisions:

1. Unit/module and required Android lanes pass.
2. No unresolved high-severity runtime or UX regressions.
3. Send timeout/cancel recovery contract remains deterministic (`UI-RUNTIME-001`).
4. Screenshot inventory check has zero required-id misses for UI-touching changes.
5. Launch gate matrix rows map to current evidence links and privacy-safe claims.

## CI Baseline

Primary workflow: `.github/workflows/ci.yml`

1. Hosted required checks: `unit-and-host-tests`, `android-lint`, `native-build-package-check`, `android-instrumented-smoke` (path-filtered where configured).
2. Governance checks run docs drift/health/accuracy and governance self-tests.
3. Nightly workflows provide additional emulator and Maestro smoke coverage; hardware closures remain separate.

## Automation Boundary

Automate by default:

1. Unit/module/runtime contract tests
2. UI wiring and deterministic flow assertions
3. Governance checks and drift reports

Human-required checkpoints:

1. Physical-device environment control and anomalies
2. Moderated usability packet evaluation
3. Final go/no-go call when multiple evidence sources conflict

## Evidence Rules

1. Raw artifacts stay under `scripts/benchmarks/runs/...`.
2. Human-readable notes stay under `docs/operations/evidence/...`.
3. Keep active notes only; prune superseded notes not referenced by active roadmap/PRD/ticket artifacts.
