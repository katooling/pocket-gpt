# MVP Implementation Tracker (Android-First)

Use this tracker to execute the six MVP stages against explicit entry/exit criteria.

## Stage Status

| Stage | Description | Primary Owner | Scaffolded in Repo | Validated on Device | Required Evidence |
|---|---|---|---|---|---|
| 1 | Android text-only runtime slice with `llama.cpp` bridge wiring | Runtime + Android | Yes | Yes | startup check output, 10-run short chat logs |
| 2 | Qwen 0.8B swap + scenario A/B thresholds | Runtime + QA | Partial (artifact/runners scaffolded) | No | benchmark CSV + threshold report |
| 3 | Routing/policy/observability integration | Runtime + Security | Yes (policy/routing scaffolds) | No | downgrade test logs, diagnostics export |
| 4 | Schema-safe tool runtime v1 | Platform + Security | Yes (schema validator + adversarial regression tests) | No | malformed call rejection tests |
| 5 | Memory v1 + image input v1 | Core/AI + Runtime | Partial (in-memory + smoke image path) | No | scenario C benchmark + quality rubric notes |
| 6 | Hardening + privacy + beta packet | Platform + QA + Product | Partial (guards/docs scaffolded) | No | soak test logs, go/no-go packet |

## Stage-wide Quality Gates (apply to every stage)

- [ ] CI green on unit tests for touched modules.
- [ ] Stage evidence artifacts committed/attached in the expected paths:
  - raw machine artifacts in `scripts/benchmarks/runs/...`
  - human evidence notes in `docs/operations/evidence/...`
- [ ] Regression checks run for previously completed stages.
- [ ] `docs/roadmap/next-steps-execution-plan.md` updated with current status/date.
- [ ] CI lane + CLI phone lane workflow kept current in `docs/testing/just-cli-android-validation-plan.md`.
- [ ] Risk register reviewed for newly introduced risks.

## Stage 1 Checklist

- [x] Build and run Stage runner with `llama.cpp` bridge
- [x] Device trust setup complete (`adb` visible, USB debugging enabled, RSA authorized)
- [x] Capture first-token and total latency
- [x] Confirm no crashes/OOM in 10 short runs
- [x] 10-run loop summary captured via `scripts/android/run_short_loop.sh`
- [x] host/JVM and Android unit lanes pass for stage changes (`:apps:mobile-android-host:test`, `:apps:mobile-android:testDebugUnitTest`)
- [x] Fast local run script and expected runtime bridge behavior documented

## Stage 2 Checklist

- [ ] Replace smoke model artifact with real Qwen `0.8B Q4`
- [ ] Run Scenario A/B from benchmark protocol
- [ ] Evaluate thresholds and record pass/fail
- [x] Add checksum verification tests for artifact manager (ENG-04 CI-first parallel scope, 2026-03-03)
- [x] Automate threshold script run in dev script (`scripts/dev/bench.sh stage2`) (2026-03-03)

## Stage 3 Checklist

- [ ] Validate low-battery downgrade behavior
- [ ] Validate high-thermal downgrade behavior
- [ ] Export diagnostics report
- [ ] Add routing policy boundary tests (battery/thermal/RAM/task matrix)
- [ ] Validate diagnostics redaction/no sensitive payload content

## Stage 4 Checklist

- [x] Run positive tool tests (calculator/date_time) (ENG-06 CI-first parallel scope, 2026-03-03)
- [x] Run malformed JSON and blocked payload tests (ENG-06 CI-first parallel scope, 2026-03-03)
- [x] Verify allowlist enforcement (ENG-06 CI-first parallel scope, 2026-03-03)
- [x] Replace ad-hoc parser with schema-driven validation (ENG-06 CI-first parallel scope, 2026-03-03)
- [x] Add security regression tests for tool payload bypass patterns (ENG-06 CI-first parallel scope, 2026-03-03)

## Stage 5 Checklist

- [ ] Validate memory retrieval relevance on follow-up prompts
- [ ] Run image path Scenario C benchmarks
- [ ] Record latency and output quality notes
- [ ] Back memory with SQLite persistence and retention pruning tests
- [ ] Add deterministic image path contract tests

## Stage 6 Checklist

- [ ] Run 30-minute soak test
- [ ] Verify resilience guards behavior
- [ ] Finalize beta go/no-go packet
- [ ] Add crash recovery tests and startup resiliency checks
- [ ] Confirm release candidate checklist is fully green
