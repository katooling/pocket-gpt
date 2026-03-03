# Test Strategy

Last updated: 2026-03-03

## Goals

1. Catch regressions early with fast local feedback.
2. Prove reliability and safety on real Android hardware.
3. Produce reproducible evidence for go/no-go decisions.

## Test Pyramid

1. **Unit tests (fast, required on every change)**
   - routing logic
   - tool validation and execution behavior
   - memory retrieval/pruning
   - policy and diagnostics helpers
2. **Module integration tests**
   - container flow: session -> prompt -> inference -> response
   - artifact checksum + model selection flow
3. **Device validation tests**
   - Scenario A/B/C benchmark protocol
   - thermal, battery, and stability checks
4. **Soak/reliability tests**
   - 30-minute sessions
   - crash/OOM/ANR observation and recovery behavior

## Release Gates

| Gate | Required For |
|---|---|
| Unit tests green on touched modules | every PR |
| No new high-severity lint/test failures | every PR |
| Scenario A/B thresholds pass on target device | Stage 2+ |
| Policy/tool safety regressions blocked | Stage 3+ |
| Scenario C + memory quality checks pass | Stage 5+ |
| Soak test + go/no-go packet complete | Stage 6 beta |

## CI Baseline (ENG-02)

Primary CI workflow:

- `.github/workflows/ci.yml`

CI runs:

1. `bash scripts/dev/verify.sh` (clean + test)
2. uploads Gradle test reports as artifacts

## Benchmark and Evidence Rules

1. Store artifacts in deterministic run folders under `scripts/benchmarks/runs/YYYY-MM-DD/<device>/`.
2. Include:
   - benchmark CSV
   - threshold report
   - logcat extract
   - optional perfetto trace
3. Do not treat mock/template data as production validation evidence.

## Ownership Split

1. Runtime team: inference performance/stability tests
2. Platform team: Android lifecycle, memory, ANR/OOM tests
3. Security team: tool/policy abuse and privacy redaction tests
4. Product QA: scenario acceptance and UX quality rubrics

## Critical Regressions (Block Release)

1. Repeatable OOM/ANR in standard benchmark loops
2. Tool execution bypassing allowlist/policy gates
3. Policy allows unintended network activity in offline mode
4. Significant first-token or throughput regression on target device class
