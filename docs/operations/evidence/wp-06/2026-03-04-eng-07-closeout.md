# ENG-07 (WP-06) Closeout Evidence - 2026-03-04

Date: 2026-03-04  
Owner: Engineering (Core/Runtime)  
Task state: Done (`ENG-07`)  
Package state: In Progress (`WP-06`, pending QA Scenario C acceptance packet)

## Closeout Objective

Land Stage-5 memory productionization core deliverables required to unblock QA-05:

1. SQLite-backed memory persistence behavior.
2. Deterministic retention/pruning enforcement with tests.
3. Deterministic image-path contract coverage for Scenario C path hardening.

## Code + Test Delta

1. Added JVM SQLite memory backend:
   - `packages/memory/src/commonMain/kotlin/com/pocketagent/memory/SqliteMemoryModule.kt`
2. Added SQLite persistence/retention tests:
   - `packages/memory/src/commonTest/kotlin/com/pocketagent/memory/SqliteMemoryModuleTest.kt`
3. Expanded in-memory pruning regression coverage:
   - `packages/memory/src/commonTest/kotlin/com/pocketagent/memory/InMemoryMemoryModuleTest.kt`
4. Added deterministic image-path contract behavior + tests:
   - `packages/inference-adapters/src/commonMain/kotlin/com/pocketagent/inference/ImageInputModule.kt`
   - `packages/inference-adapters/src/commonTest/kotlin/com/pocketagent/inference/SmokeImageInputModuleTest.kt`
5. Fixed quick-lane environment-determinism bug uncovered during closeout verification:
   - `tools/devctl/doctor.py` (`env={}` now respected as explicit env input)
6. Added runtime follow-up memory relevance regression coverage:
   - `apps/mobile-android/src/test/kotlin/com/pocketagent/android/AndroidMvpContainerTest.kt`

## Behavior Validated

1. Memory persistence:
   - chunks written by one `SqliteMemoryModule` instance are retrievable after module re-instantiation against the same DB path.
2. Retention/pruning:
   - `pruneMemory(maxChunks)` removes oldest rows first (`created_at_epoch_ms ASC, id ASC`) and returns deleted count.
3. Image contract determinism:
   - output is normalized and stable (`v=1`, normalized extension, whitespace-collapsed/truncated prompt, non-negative token cap).

## Commands Run and Outcomes

1. `./gradlew --no-daemon :packages:memory:test`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `scripts/benchmarks/runs/2026-03-04/ci-host/eng-07-closeout/01-memory-test.log`
2. `./gradlew --no-daemon :packages:inference-adapters:test`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `scripts/benchmarks/runs/2026-03-04/ci-host/eng-07-closeout/02-inference-adapters-test.log`
3. `bash scripts/dev/test.sh quick`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `scripts/benchmarks/runs/2026-03-04/ci-host/eng-07-closeout/03-test-quick.log`
4. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest --tests "com.pocketagent.android.AndroidMvpContainerTest"`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `docs/operations/evidence/wp-06/2026-03-04-eng-07-androidmvp-test.log`

## Raw Artifacts

1. `scripts/benchmarks/runs/2026-03-04/ci-host/eng-07-closeout/context.txt`
2. `scripts/benchmarks/runs/2026-03-04/ci-host/eng-07-closeout/01-memory-test.log`
3. `scripts/benchmarks/runs/2026-03-04/ci-host/eng-07-closeout/02-inference-adapters-test.log`
4. `scripts/benchmarks/runs/2026-03-04/ci-host/eng-07-closeout/03-test-quick.log`

## QA Unblock Statement

Engineering deliverables that blocked QA-05 prep are now present:

1. SQLite persistence path and retention/pruning tests are implemented.
2. Deterministic Scenario C image-path contract tests are implemented.

QA can proceed with final device-side Scenario C + memory acceptance execution (`QA-05`) using current WP-06 code state.
