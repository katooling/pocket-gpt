# Test Strategy

Last updated: 2026-03-03

## Source of truth

- Command contract: `scripts/dev/README.md`
- Android device execution details: `docs/testing/android-dx-and-test-playbook.md`

## Goals

1. Catch regressions early with fast local feedback.
2. Prove reliability and safety on real Android hardware.
3. Produce reproducible evidence for go/no-go decisions.

## Test Pyramid

1. Unit tests (required on every change)
2. Module integration tests
3. Device validation tests (Scenario A/B/C)
4. Soak/reliability tests

## Release Gates

| Gate | Required For |
|---|---|
| Unit tests green on touched modules | every PR |
| No new high-severity lint/test failures | every PR |
| Scenario A/B thresholds pass on target device | Stage 2+ |
| Policy/tool safety regressions blocked | Stage 3+ |
| Scenario C + memory quality checks pass | Stage 5+ |
| Soak test + go/no-go packet complete | Stage 6 beta |

## CI Baseline

Primary workflow: `.github/workflows/ci.yml`

CI responsibilities:

1. `python3 tools/devctl/main.py lane test ci`
2. `bash scripts/dev/docs-drift-check.sh`
3. `bash scripts/dev/evidence-check-changed.sh` for changed evidence notes
4. `bash scripts/dev/governance-self-test.sh`
5. PR governance checks (template completion)
6. Test report artifact upload

## Automation Boundary (CI vs Human)

Automate by default:

1. Unit/module tests for touched modules
2. Contract/failure-path tests for runtime wiring/policies
3. Deterministic script execution and artifact generation
4. Crash/OOM signal scanning from collected logs

Human-required checkpoints:

1. USB trust bootstrap (developer mode + RSA authorization)
2. Physical environment control during benchmark windows
3. Anomaly adjudication when metrics/logs conflict

## Artifact/Evidence Policy

1. Raw machine artifacts only in `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...`
2. Human evidence notes/status only in `docs/operations/evidence/wp-xx/...`
3. Stage closure requires evidence note linking raw artifact paths

## Android Automation Lanes

1. Espresso instrumentation lane: `python3 tools/devctl/main.py lane android-instrumented`
2. Maestro E2E lane: `python3 tools/devctl/main.py lane maestro`
