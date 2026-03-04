# WP-12 Handover Ticket Packet

Last updated: 2026-03-04
Owner: Product Lead
Status: Ready for dispatch

## Summary

Product has unblocked `ENG-12` with a locked decision:

1. Side-load/manual-internal only.
2. Strict manifest + SHA + provenance hard-block policy.

This packet splits WP-12 execution into decision-complete tickets so owners can execute without re-deciding scope.

## Ground Truth Context (Already Done)

1. Runtime truth gate is in place and closure-path startup checks block `ADB_FALLBACK` by default:
   - `docs/operations/evidence/wp-12/2026-03-04-eng-11-runtime-truth-gate.md`
2. Product model-distribution decision is approved and unblocked:
   - `docs/operations/evidence/wp-12/2026-03-04-prod-eng-12-model-distribution-decision.md`
3. WP-12 is active on the board with `ENG-12` in progress and `ENG-13..ENG-17` queued:
   - `docs/operations/execution-board.md`
4. Ops framework references:
   - `docs/operations/README.md`
   - `docs/operations/role-playbooks/engineering-playbook.md`
   - `docs/operations/role-playbooks/qa-playbook.md`
   - `docs/testing/test-strategy.md`
   - `docs/testing/android-dx-and-test-playbook.md`

## Ownership and Execution Order

### Owners

1. Runtime Eng Lead (primary): `ENG-12`, `ENG-13`, `ENG-16`
2. Core Eng: `ENG-14`
3. Platform Eng: `ENG-15`
4. Security Eng: `ENG-17`
5. QA Lead/Engineer: WP-12 validation matrix + closeout reruns
6. Product Lead: acceptance/signoff only (no remaining decision gate for `ENG-12`)

### Execution Order

1. `ENG-12` and `ENG-13` parallel kickoff.
2. `ENG-14`, `ENG-15`, `ENG-16` start once `ENG-12` artifact-path contracts are stable.
3. `ENG-17` runs after runtime/data paths are integrated enough to test platform policy wiring.
4. `QA-WP12` closeout runs after ENG evidence notes (`ENG-12..ENG-17`) are landed.

## Ready-to-File Tickets

## Ticket 1: ENG-12 Implementation (Runtime Eng)

- Title: `ENG-12 | Side-load model distribution + strict provenance hard-block policy`
- Status: In Progress
- Owner: Runtime Eng
- Prereq: `ENG-11A`, product decision note approved

### Objective

Implement one production model distribution path: manual/internal side-load with strict artifact verification before load.

### In Scope

1. Side-load intake path for GGUF artifacts (internal/manual flow).
2. Manifest lookup for model id + version.
3. `SHA-256` verification.
4. Provenance signature/issuer verification.
5. Runtime compatibility validation.
6. Deterministic hard-fail error contract on any verification failure.
7. Last-known-good artifact retention fallback only when already verified.

### Out of Scope

1. PAD path.
2. First-launch network download path.
3. Any cloud model fetch.

### Required Commands

1. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
2. `bash scripts/dev/test.sh quick`

### Evidence Output

1. `docs/operations/evidence/wp-12/YYYY-MM-DD-eng-12-model-distribution-implementation.md`
2. Raw verification artifacts under `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...` when device-side is used.

### Acceptance Criteria

1. Unverified model cannot be loaded.
2. Verified model loads deterministically.
3. Error contracts are deterministic and test-covered for each failure mode.
4. Execution board + engineering playbook are updated.

## Ticket 2: ENG-13 Runtime Proof (Runtime Eng + QA Support)

- Title: `ENG-13 | Native JNI inference proof on Samsung + perf/memory characterization (0.8B/2B)`
- Status: In Progress
- Owner: Runtime Eng
- Support: QA

### Objective

Produce real-device proof that native JNI runtime executes real inference and capture first-token/decode/PSS/OOM behavior for `0.8B` and `2B`.

### In Scope

1. Android ARM native build artifact path (JNI + libs) validated on target Samsung device.
2. Runtime proof logs showing `NATIVE_JNI` backend in closure-path runs.
3. Benchmark scenarios for both model sizes.
4. Memory characterization (`PSS`, OOM/ANR/crash signals).
5. Threshold/evidence packet for QA consumption.

### Required Commands

1. `bash scripts/android/ensure_device.sh`
2. `bash scripts/android/run_stage_checks.sh`
3. `bash scripts/dev/bench.sh stage2 --device <device-id> --date <YYYY-MM-DD>`
4. `python3 scripts/benchmarks/evaluate_thresholds.py <stage-2-threshold-input.csv>`
5. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
6. `bash scripts/dev/test.sh quick`

### Evidence Output

1. `docs/operations/evidence/wp-12/YYYY-MM-DD-eng-13-native-runtime-proof.md`
2. Raw runs in `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...`
3. Include scenario A/B CSVs, threshold report, logcat, notes, and memory/PSS snapshots.

### Acceptance Criteria

1. Evidence explicitly shows backend is `NATIVE_JNI`.
2. `0.8B` and `2B` have measured first-token and decode throughput.
3. Memory behavior is documented with no placeholder path.
4. QA receives reproducible run-directory links.

## Ticket 3: ENG-14 Android-Native Memory Backend (Core Eng)

- Title: `ENG-14 | Replace JVM-JDBC memory path with Android-native SQLite backend`
- Status: Ready
- Owner: Core Eng
- Prereq: `ENG-12` artifact-path contract stable

### Objective

Ensure runtime memory persistence on Android does not rely on JVM-only JDBC assumptions.

### In Scope

1. Introduce Android-native storage implementation.
2. Migrate runtime wiring to Android-native backend.
3. Retention/pruning parity with current behavior.
4. Regression tests for save/retrieve/prune semantics.

### Required Commands

1. `./gradlew --no-daemon :packages:memory:test`
2. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
3. `bash scripts/dev/test.sh quick`

### Evidence Output

1. `docs/operations/evidence/wp-12/YYYY-MM-DD-eng-14-android-native-memory.md`

### Acceptance Criteria

1. Android runtime path does not require JDBC driver.
2. Behavioral parity tests pass.
3. No retention/policy regressions.

## Ticket 4: ENG-15 Real Tool Data Stores (Platform Eng)

- Title: `ENG-15 | Replace placeholder notes/search/reminder responses with real local stores`
- Status: Ready
- Owner: Platform Eng
- Prereq: `ENG-12` artifact-path contract stable

### Objective

Move tool runtime from placeholder strings to real on-device data operations.

### In Scope

1. `notes_lookup` backed by real notes store.
2. `local_search` backed by real local index/store.
3. `reminder_create` backed by real reminder persistence.
4. Keep schema safety and deterministic error contracts.

### Required Commands

1. `./gradlew --no-daemon :packages:tool-runtime:test`
2. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
3. `bash scripts/dev/test.sh quick`

### Evidence Output

1. `docs/operations/evidence/wp-12/YYYY-MM-DD-eng-15-tool-store-integration.md`

### Acceptance Criteria

1. No placeholder success strings on production path.
2. Tool safety regressions remain blocked.
3. Data operations are local-first and test-covered.

## Ticket 5: ENG-16 Real Multimodal Image Path (Runtime Eng)

- Title: `ENG-16 | Replace smoke image adapter with production multimodal runtime path`
- Status: Ready
- Owner: Runtime Eng
- Prereq: `ENG-12` artifact-path contract stable

### Objective

Remove smoke-only image analysis from the production lane and wire a real runtime-backed image path.

### In Scope

1. Production image inference adapter and wiring.
2. Existing validation behavior retained (input/path/type checks).
3. Runtime lifecycle integration (load/generate/unload).
4. Tests for pass/fail and deterministic error contracts.

### Required Commands

1. `./gradlew --no-daemon :packages:inference-adapters:test`
2. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
3. `bash scripts/dev/test.sh quick`

### Evidence Output

1. `docs/operations/evidence/wp-12/YYYY-MM-DD-eng-16-image-runtime-path.md`

### Acceptance Criteria

1. Production path is not `SmokeImageInputModule`.
2. Real runtime invocation is evidenced.
3. Privacy/policy expectations remain intact.

## Ticket 6: ENG-17 Platform Network Policy Wiring (Security Eng)

- Title: `ENG-17 | Wire policy module to Android platform network behavior + regressions`
- Status: Ready
- Owner: Security Eng
- Support: Runtime Eng
- Prereq: Runtime/data path integration from `ENG-12..ENG-16`

### Objective

Close the gap between policy checks and Android platform-level network enforcement behavior.

### In Scope

1. Platform wiring for network deny/allow semantics.
2. Manifest/security-config/client-level enforcement checks.
3. Regression tests for offline-only guarantees.
4. Deterministic diagnostics for policy denials.

### Required Commands

1. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
2. `python3 tools/devctl/main.py lane android-instrumented`
3. `bash scripts/dev/test.sh quick`

### Evidence Output

1. `docs/operations/evidence/wp-12/YYYY-MM-DD-eng-17-network-policy-wiring.md`

### Acceptance Criteria

1. Offline-only mode cannot perform disallowed network actions.
2. Policy result is reflected in runtime behavior, not only a logical check.
3. Tests/evidence explicitly map to privacy-model claims.

## Ticket 7: QA-WP12 Closeout Packet (QA Engineer)

- Title: `QA-WP12 | Validate backend production runtime closure packet (ENG-12..ENG-17)`
- Status: Ready (execute after ENG notes land)
- Owner: QA Engineer
- Prereq: Evidence notes for `ENG-12..ENG-17`

### Objective

Re-run validation after `ENG-12..ENG-17` and publish final WP-12 QA recommendation.

### In Scope

1. Reproduce key ENG scenarios on physical device.
2. Validate native runtime proof (`NATIVE_JNI`) and artifact verification failures.
3. Validate thresholds/logcat and runtime-policy behavior.
4. Publish pass/fail recommendation for WP-12 closure.

### Required Commands

1. `bash scripts/android/ensure_device.sh`
2. `bash scripts/android/run_stage_checks.sh`
3. `bash scripts/dev/bench.sh stage2 --device <device-id> --date <YYYY-MM-DD>`
4. `python3 scripts/benchmarks/evaluate_thresholds.py <stage-2-threshold-input.csv>`

### Evidence Output

1. `docs/operations/evidence/wp-12/YYYY-MM-DD-qa-wp12-closeout.md`
2. Raw artifacts in `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...`

### Acceptance Criteria

1. Final QA note references only real run artifacts.
2. Final recommendation explicitly states: close WP-12 or list blockers with owner.

## Required Process Updates Per Ticket

For every ticket above, assignee must do all four:

1. Update `docs/operations/execution-board.md` status first.
2. Mirror status in role playbooks:
   - Engineering: `docs/operations/role-playbooks/engineering-playbook.md`
   - QA: `docs/operations/role-playbooks/qa-playbook.md`
   - Product: `docs/operations/role-playbooks/product-playbook.md`
3. Execute only command contracts in `scripts/dev/README.md` and test gates in `docs/testing/test-strategy.md`.
4. Publish dated evidence note under `docs/operations/evidence/wp-12/`.

## Assumptions and Defaults

1. Date format is `YYYY-MM-DD` (current cycle date: `2026-03-04`).
2. Primary target device is Samsung `RR8NB087YTF` unless QA declares a device change.
3. Side-load is the only `ENG-12` distribution path in this phase.
4. Execution remains within `WP-12`; `WP-09` and `WP-10` scope is unchanged.
