# Test Strategy

Last updated: 2026-03-04

## Source of truth

- Command contract: `scripts/dev/README.md`
- Android device execution details: `docs/testing/android-dx-and-test-playbook.md`

## Goals

1. Catch regressions early with fast local feedback.
2. Prove reliability and safety on real Android hardware.
3. Prove user-facing MVP usability through deterministic UI acceptance flows.
4. Produce reproducible evidence for go/no-go decisions.

## Test Pyramid

1. Unit tests (required on every change)
2. Module integration tests
3. UI state/wiring tests (Compose + ViewModel + façade)
4. Device validation tests (Scenario A/B/C + UI acceptance)
5. Soak/reliability tests

## Lane Architecture and Ownership Matrix

| Lane | Owner | Goal | Cadence | Main Commands | Primary Artifacts |
|---|---|---|---|---|---|
| Dev fast lane | Engineering | Catch regressions before PR | every change | `bash scripts/dev/test.sh quick` | local test output |
| Merge gate lane | Engineering + CI | enforce baseline quality gates | every PR | `python3 tools/devctl/main.py lane test ci` + governance checks | CI reports and artifacts |
| QA device lane | QA | verify real-device UI/runtime behavior | weekly + pre-promotion | `python3 tools/devctl/main.py lane android-instrumented`, `python3 tools/devctl/main.py lane maestro`, `bash scripts/dev/device-test.sh` | weekly QA packets under `docs/operations/evidence/wp-09/` |
| Runtime proof lane | Engineering + QA | closure-path native runtime evidence | ENG-13/WP-12 closure windows | `bash scripts/dev/bench.sh stage2 --device <id>` + evidence validator | `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...` |
| Product usability lane | Product + QA + Design | release decision from usability signals | beta cohort windows | WP-13 usability packet workflow | `docs/operations/evidence/wp-13/...` |

## Core Flow Coverage Matrix (Mandatory)

| Core Flow | Unit/Module Coverage | Instrumented Coverage | Maestro Coverage | Device/Operational Coverage | Owner |
|---|---|---|---|---|---|
| Startup and launch status | `ChatViewModelTest`, startup-check mapping tests | launch smoke with runtime-status assertions | scenario launch step | stage checks baseline signal | Eng + QA |
| Send and streaming response | ViewModel stream tests | send-message UI smoke | `tests/maestro/scenario-a.yaml` | weekly matrix run | Eng + QA |
| Session create/switch/restore | ViewModel + persistence codec tests | drawer/session sanity checks | scenario continuity check (weekly extension) | weekly matrix run | Eng + QA |
| Image attach success/failure | ViewModel/image module tests | image attach instrumentation checks | optional workflow in QA runs | required-tier weekly checks | Eng + QA |
| Tool invocation + safety rejection | tool runtime/schema tests + ViewModel tool mapping | natural-language tool UI assertions | tool flow in `tests/maestro/scenario-b.yaml` | weekly matrix + security review | Eng + QA + Security |
| Diagnostics export + redaction | diagnostics/redaction tests | diagnostics UI assertion | scenario-b diagnostics step | QA release promotion checklist | Eng + QA + Security |
| Offline/network policy enforcement | policy/network tests | launch sanity in offline mode | n/a | WP-12/weekly policy references | Eng + QA + Security |
| Runtime/model readiness transitions | ViewModel runtime state tests | status-chip and advanced-sheet checks | n/a | stage-2 and startup checks | Eng + QA |

Coverage rule:

1. Every core flow must have at least one deterministic unit/module assertion.
2. User-visible critical flows must also have instrumentation or Maestro assertion.
3. Runtime/performance assertions remain in device lanes only.

## Release Gates

| Gate | Required For |
|---|---|
| Unit tests green on touched modules | every PR |
| No new high-severity lint/test failures | every PR |
| Scenario A/B thresholds pass on target device | Stage 2+ |
| Policy/tool safety regressions blocked | Stage 3+ |
| Scenario C + memory quality checks pass | Stage 5+ |
| Soak test + go/no-go packet complete | Stage 6 beta |
| Closure-path startup checks fail on `ADB_FALLBACK` backend unless explicitly override-enabled for local scaffolding | Stage 6.5+ |
| UI-01..UI-10 acceptance suite pass | WP-11 close + external beta signoff |

## CI Baseline

Primary workflow: `.github/workflows/ci.yml`

CI responsibilities:

1. `python3 tools/devctl/main.py lane test ci`
2. `python3 tools/devctl/main.py governance docs-drift`
3. `python3 tools/devctl/main.py governance evidence-check-changed` for changed evidence notes
4. `python3 tools/devctl/main.py governance self-test`
5. PR governance checks (template completion)
6. Test report artifact upload

CI note:

1. Device-dependent lanes (`android-instrumented`, `maestro`, `stage2`) are intentionally not part of baseline CI.
2. Promotion decisions must include latest QA device evidence links.

## UI Test Lanes

1. Compose/ViewModel JVM tests: `./gradlew :apps:mobile-android:testDebugUnitTest`
2. Instrumentation smoke + UI assertions: `python3 tools/devctl/main.py lane android-instrumented`
3. Maestro E2E UI scenarios:
   - `tests/maestro/scenario-a.yaml`
   - `tests/maestro/scenario-b.yaml`

## Runtime Lane Optimization Rules

1. Prefer model-sweep execution that reuses a loaded model for Scenario A/B in one instrumentation invocation.
2. For local iteration, use `POCKETGPT_STAGE2_SKIP_INSTALL=1` after APKs are already installed.
3. Use low-run/token profile for rapid iteration (`RUNS=1`, `MAX_TOKENS_A/B=4`, `WARMUP_MAX_TOKENS=0`); rerun closure packet with closure-approved profile before evidence signoff.
4. Keep Stage-2 env contracts stable (`POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH`, `POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH`) across fast and closure profiles.
5. Run directory must be fresh/clean per invocation to prevent stale artifact contamination in validator outcomes.

## Automation Boundary (CI vs Human)

Automate by default:

1. Unit/module tests for touched modules
2. Contract/failure-path tests for runtime wiring/policies
3. UI state/wiring tests and deterministic UI flow assertions
4. Deterministic script execution and artifact generation
5. Crash/OOM signal scanning from collected logs

Human-required checkpoints:

1. USB trust bootstrap (developer mode + RSA authorization)
2. Physical environment control during benchmark/soak windows
3. Anomaly adjudication when metrics/logs conflict
4. Final UX quality call when behavior passes tests but usability concerns remain

## Artifact/Evidence Policy

1. Raw machine artifacts only in `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...`
2. Human evidence notes/status only in `docs/operations/evidence/wp-xx/...`
3. Stage closure requires evidence note linking raw artifact paths
4. WP-11 closure requires both:
   - raw UI run outputs (instrumentation/Maestro/device lane)
   - product-readable UX acceptance note (`docs/operations/evidence/wp-11/...`)

## Android Automation Lanes

1. Espresso instrumentation lane: `python3 tools/devctl/main.py lane android-instrumented`
2. Maestro E2E lane: `python3 tools/devctl/main.py lane maestro`

## Non-Blocking Sequencing Rule (ENG-13 Safe)

1. Test improvements can proceed in parallel with ENG-13 as long as they do not change:
   - Stage-2 command contracts
   - runtime evidence validator rules
   - side-load model env variable contracts
2. During ENG-13 active execution, prioritize:
   - unit and instrumentation assertions
   - docs and checklist alignment
   - QA/Product gate packet quality
3. Defer runtime-lane contract changes until ENG-13 closure evidence is accepted.
