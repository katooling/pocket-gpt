# Next Steps Execution Plan (Android-First MVP)

Last updated: 2026-03-03

This plan turns current scaffolding into validated MVP capability with explicit DX, test, and reliability gates per stage.

## Stage 0 - Build/CI Baseline

Goal: fast, repeatable local and CI iteration.

Deliverables:

1. Add Gradle wrapper and pinned toolchain.
2. Add CI pipeline for module tests and Android app tests.
3. Add one-command stage smoke runner.

Exit criteria:

1. Fresh checkout can run tests with one command.
2. CI passes on every PR for touched modules.
3. Test results are visible and actionable.

## Stage 1 - Real Runtime Slice

Goal: replace smoke inference path with real Android baseline runtime integration.

Deliverables:

1. Real `llama.cpp` bridge in Android runtime path.
2. Stage runner wired to real model load/generate lifecycle.
3. First-token/total latency metrics captured from real runtime.

Exit criteria:

1. Scenario A can execute on physical Android device.
2. No crash/OOM across 10 short runs.
3. Unit and integration tests pass for runtime wiring.

## Stage 2 - Artifact and Benchmark Reliability

Goal: make model packaging/checksum and threshold evaluation reliable.

Deliverables:

1. Artifact manifest + checksum verification + version handling.
2. Benchmark run folder convention adopted for all executions.
3. Threshold evaluation included in routine validation flow.

Exit criteria:

1. Checksum pass/fail cases are covered by tests.
2. Scenario A/B evidence exists for at least one physical mid-tier Android device.
3. Threshold report is produced and attached for each run.

## Stage 3 - Policy/Routing/Observability Hardening

Goal: enforce downgrade and privacy-safe diagnostics behavior.

Deliverables:

1. Routing matrix coverage for battery/thermal/RAM/task combinations.
2. Policy checks integrated into runtime action flow.
3. Diagnostics export includes required metrics without sensitive payload content.

Exit criteria:

1. Downgrade behavior validated on low-battery and high-thermal conditions.
2. Routing/policy tests cover boundary conditions.
3. Diagnostics export evidence attached in run artifacts.

## Stage 4 - Tool Runtime Safety

Goal: move from lightweight validation to strict, testable safety guarantees.

Deliverables:

1. Schema-driven tool argument validation.
2. Allowlist enforcement with negative/adversarial tests.
3. Deterministic tool behavior with clear error contracts.

Exit criteria:

1. Malformed payload and injection-style tests are green.
2. No bypass path for non-allowlisted tools.
3. Tool suite runs in CI for every tool-runtime change.

## Stage 5 - Memory + Image Productionization

Goal: replace in-memory scaffolds with production-ready persistence and image path behavior.

Deliverables:

1. SQLite-backed memory implementation with pruning/retention enforcement.
2. Stage image scenario path integrated with runtime contracts.
3. Retrieval relevance and latency regression tests.

Exit criteria:

1. Memory survives app process restarts.
2. Scenario C runs on device with recorded metrics and quality notes.
3. Retention and pruning behavior is validated by tests.

## Stage 6 - Beta Hardening and Decision

Goal: gather confidence evidence and make a defensible go/no-go call.

Deliverables:

1. 30-minute soak runs on target device classes.
2. Crash/OOM/ANR evidence and diagnostics archive.
3. Completed go/no-go packet with risk review updates.

Exit criteria:

1. Scenario A/B/C thresholds pass on target Android mid-tier devices.
2. No repeatable blocker issues in soak runs.
3. Beta go/no-go decision recorded with evidence links.

## Stage 7 - Post-MVP Voice Foundation (Next Horizon)

Goal: establish production-ready voice path without breaking local-first guarantees.

Deliverables:

1. STT integration strategy (offline-preferred, fallback policy-defined).
2. TTS output mode with latency/power quality benchmarks.
3. Voice privacy controls aligned with text/image privacy rules.

Exit criteria:

1. Voice roundtrip works on target devices under defined latency budget.
2. Voice path has benchmark evidence and battery impact report.
3. Policy and safety tests cover voice-triggered actions.

## Cross-Stage Engineering Rules

1. Every code change ships with tests for new behavior and regression risk.
2. Keep local iteration fast:
   - run smallest relevant test scope first
   - keep long-running tests isolated in dedicated suites
3. Keep evidence reproducible:
   - use deterministic artifact paths
   - timestamp and device-label all run outputs
4. Update tracker and this plan at each stage boundary:
   - `docs/roadmap/mvp-implementation-tracker.md`
   - `docs/roadmap/mvp-beta-go-no-go-packet.md`
5. Keep cross-functional alignment current:
   - `docs/roadmap/product-roadmap.md`
   - `docs/roadmap/team-workstreams.md`
   - `docs/product/open-questions-log.md`
