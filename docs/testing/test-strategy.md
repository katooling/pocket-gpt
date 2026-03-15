# Test Strategy (Canonical Playbook)

Last updated: 2026-03-15

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
| Startup/readiness | Runtime startup checks + ViewModel mapping tests | `android-instrumented` smoke + lifecycle E2E gate | Eng + QA |
| Send/streaming | stream event reducer + timeout/cancel tests | `journey` send-capture evidence + lifecycle E2E gate | Eng + QA |
| Session continuity | persistence/session tests | weekly regression matrix evidence | Eng + QA |
| Image attach | runtime/image contract tests | maestro scenario coverage | Eng + QA |
| Tool safety contracts | tool-runtime schema tests + typed tool result mapping | local-tool evidence in QA matrix/usability packet | Eng + QA + Security |
| Privacy controls and redaction | diagnostics redaction tests + privacy UI checks | privacy claim-parity ticket evidence | Eng + QA + Security |
| Model setup recovery | provisioning/viewmodel tests | first-run lifecycle E2E + recovery evidence in WP-13 packet | Eng + QA + Product |

## Environment Decision Matrix

| Environment | Strengths | Limits | Best Use |
|---|---|---|---|
| Local host/unit lanes | fastest turnaround; lowest setup cost; easy debug cycle | cannot validate full Android UI/runtime behavior | per-save iteration, contract and reducer tests |
| Local Android device (`devctl`) | production-like behavior, richest diagnostics (`journey-report.json`, local screenshots/logcat), deterministic preflight contracts | limited parallelism; depends on attached hardware state | root-cause debugging, release/promotion confidence, runtime closure |
| CI emulator lanes | deterministic and repeatable required checks; easy branch protection integration | slower than local loop; emulator fidelity limits | required PR/main gates and broad baseline confidence |
| Maestro Cloud (supplemental) | hosted fan-out; parallel suite expansion; shared cloud reports | queue/network variance; not a replacement for local preflight contracts | nightly/regression expansion, cross-config supplemental coverage |

## Value-Per-Minute Cadence

| When | Primary Commands | Target Runtime | Decision Value |
|---|---|---|---|
| Per-save | `bash scripts/dev/test.sh fast` | 2-8 min | catches common logic regressions early |
| Targeted bug repro loop | scoped Maestro flow in `tmp/` + logcat dump/scan (see `docs/testing/runbooks.md`) | 3-15 min | fastest device-level crash signature isolation for one path |
| Pre-push | `bash scripts/dev/test.sh merge` | 10-25 min | validates merge-equivalent safety net |
| Runtime/UI change local check | `python3 tools/devctl/main.py lane android-instrumented` + `python3 tools/devctl/main.py lane maestro` | 10-35 min | validates on-device runtime/UI wiring |
| PR high-risk gate | CI `lifecycle-e2e-first-run` | 10-35 min | blocks critical lifecycle regressions before merge |
| Every `main` push | CI `lifecycle-e2e-first-run` (required) | 10-35 min | protects your direct-to-main development path |
| Nightly | emulator matrix + Maestro smoke + first-run lifecycle + optional cloud-first-run | 45-180 min | catches drift/flakes across wider configs |
| Weekly release rehearsal | stage-2/hardware closure lanes + evidence packet | half-day | final production-like launch confidence |

## Lane Policy

1. `bash scripts/dev/test.sh fast` for fast changed-file confidence.
2. scoped Maestro + logcat loop for device-specific crash/hang debugging only (not merge/release signoff).
3. `python3 tools/devctl/main.py gate merge-unblock` for day-to-day unblock safety.
4. `python3 tools/devctl/main.py gate promotion [--include-screenshot-pack]` for promotion readiness.
5. `bash scripts/dev/test.sh merge` remains the canonical broad merge-equivalent unit/contract lane.
6. `python3 tools/devctl/main.py lane android-instrumented` for Android smoke.
7. `python3 tools/devctl/main.py lane maestro` for E2E app workflows; prefer tag-scoped runs when you only need one risk slice (for example `--include-tags smoke` or `--include-tags model-management`).
8. `python3 tools/devctl/main.py lane journey` for strict send/runtime journey evidence; add `--steps instrumentation,send-capture,maestro` only when you explicitly want Maestro replay in the same lane.
9. `python3 tools/devctl/main.py lane screenshot-pack [--product-signal-only]` for UI screenshot contract.
10. Stage-2 runtime closure lanes remain physical-device signoff lanes.

## Merge-Unblock vs Promotion Gates

1. Merge-unblock gate contract:
   - `merge` + `doctor` + `android-instrumented`
   - risk-triggered lifecycle flow (`tests/maestro/scenario-first-run-download-chat.yaml`)
2. Promotion gate contract:
   - `merge` + `doctor` + `android-instrumented` + `maestro` + strict `journey`
   - optional `screenshot-pack` via `--include-screenshot-pack`
3. Gate reports are emitted under `build/devctl/gates/` and include:
   - per-step duration (runtime signal)
   - per-step correctness classification (`pass`, `product_signal_fail`, `harness_noise_fail`, `infra_fail`)
   - blocking/non-blocking decision used by the gate
4. Product-signal-only policy:
   - known harness-noise failures in selected expensive lanes (currently strict kickoff-harness journey failures and screenshot-pack compose-harness failures) are recorded as caveats, not blockers, in promotion gating.

## Risk-Based Lifecycle Gate Policy

1. Required CI job name: `lifecycle-e2e-first-run`.
2. PRs run this gate when either:
   - PR label is one of `risk:e2e-lifecycle`, `risk:runtime`, `risk:provisioning`, or
   - high-risk paths change (mobile runtime/provisioning/download/chat and shared app-runtime/native-bridge paths).
3. Every push to `main` runs `lifecycle-e2e-first-run` and blocks on failure.
4. Lifecycle gate executes `tests/maestro/scenario-first-run-download-chat.yaml`.
5. Gate allows one bounded clean-state retry; first-failure artifacts are preserved for triage.

## CI Baseline

Primary workflow: `.github/workflows/ci.yml`

1. Hosted required checks: `unit-and-host-tests`, `android-lint`, `native-build-package-check`, `android-instrumented-smoke`, `lifecycle-e2e-first-run` (risk-conditional on PRs, always-on for `main`).
2. `android-instrumented-smoke` is intentionally scoped to two deterministic checks: onboarding completion (`MainActivityUiSmokeTest#onboardingFlowCanProgressAndComplete`) plus the focused model-management Compose contract (`ModelManagementSheetComposeContractTest`). Full first-run/download/send behavior stays in lifecycle E2E.
3. Governance checks run docs drift/health/accuracy and governance self-tests.
4. Nightly workflows provide emulator matrix coverage and Maestro supplemental coverage (including first-run lifecycle and the model-management split smoke); cloud runs execute when API key is configured and should stay tag-scoped and short.
5. Required checks for branch protection should include `lifecycle-e2e-first-run`.

## Engineering Principles (Applied)

1. Layered pyramid: most tests stay at unit/contract level; E2E guards only core lifecycle risk.
2. Risk-based E2E: expensive flows run when risk is high or branch is critical (`main`).
3. Flake containment: bounded retry is explicit, artifacts retained, and failures are visible.
4. Deterministic evidence: release/promotion decisions rely on reproducible artifacts, not ad-hoc re-runs.

## Lessons Learned (Repo-Specific)

1. Local `devctl` lanes are the fastest path to root cause because they bundle preflight checks, provisioning sanity, structured runtime snapshots, screenshots, and logcat in one run.
2. CI emulators are the best place for deterministic required checks that protect `main` and enforce contracts consistently.
3. Cloud runs are most useful for supplemental fan-out and hosted reports, not as the only release gate.
4. First-run lifecycle failures can be environment-sensitive; preserving first-attempt artifacts is essential even when retry passes.
5. Cloud smoke should validate one focused contract per flow; benchmark and qualification paths should be tagged separately and kept out of smoke/default fan-out.

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

1. Raw artifacts stay under `tmp/devctl-artifacts/...` for `devctl` local lanes, `scripts/benchmarks/runs/...` for stage-2 benchmark evidence, or uploaded CI artifacts.
2. Human-readable notes stay under `docs/operations/evidence/...`.
3. Keep active notes only; prune superseded notes not referenced by active roadmap/PRD/ticket artifacts.
