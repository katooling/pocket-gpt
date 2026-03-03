# ENG-04 (WP-03) Closeout Evidence - 2026-03-03

Date: 2026-03-03  
Task state: Done  
Package state: WP-03 remains In Progress pending QA-02 final closure rerun

## Closeout Objective

Remove checksum placeholder ambiguity from the active Stage-2 runtime closure path and prove artifact-validation behavior is enforced by runtime startup checks plus checksum contract tests.

## Final Artifact-Validation Behavior

1. Active Android runtime registration path no longer hardcodes placeholder checksum strings.
2. Runtime artifact SHA-256 values are now sourced from explicit environment inputs:
   - `POCKETGPT_QWEN_3_5_0_8B_Q4_SHA256`
   - `POCKETGPT_QWEN_3_5_2B_Q4_SHA256`
3. `runStartupChecks()` now validates artifact manifest metadata before runtime load checks.
4. If checksum metadata is missing or malformed, startup checks fail with an artifact-manifest error and Stage-2 closure claims must not proceed.

## Code + Test Changes

1. Runtime startup guard + checksum-source wiring:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AndroidMvpContainer.kt`
2. Android container tests for startup manifest behavior:
   - `apps/mobile-android/src/test/kotlin/com/pocketagent/android/AndroidMvpContainerTest.kt`
3. Artifact manager test extension for real-format checksum manifest acceptance:
   - `packages/inference-adapters/src/commonTest/kotlin/com/pocketagent/inference/ModelArtifactManagerTest.kt`

## Contract Coverage (Checksum + Manifest)

1. Checksum pass/mismatch coverage: verified in `ModelArtifactManagerTest`.
2. Unknown model/unknown version coverage: verified in `ModelArtifactManagerTest`.
3. Manifest validation failure coverage (invalid checksum format): verified in `ModelArtifactManagerTest`.
4. Manifest validation pass coverage (64-hex checksum format): verified in `ModelArtifactManagerTest`.
5. Runtime startup manifest enforcement coverage (missing checksum metadata fails startup): verified in `AndroidMvpContainerTest`.
6. Runtime startup happy path coverage (valid checksum metadata + ready models passes startup checks): verified in `AndroidMvpContainerTest`.

## Commands Run

1. `./gradlew --no-daemon :packages:inference-adapters:test` -> PASS (`BUILD SUCCESSFUL`)
2. `bash scripts/dev/test.sh quick` -> PASS (`BUILD SUCCESSFUL`)

## Device Verification Scope

1. Device-side rerun is not part of this ENG-04 closeout proof.
2. QA-02 final closure rerun remains the required device-stage step to finalize WP-03 package closure.

## QA Handoff / Unblock

1. QA is explicitly unblocked for final QA-02 closure rerun on the artifact-validated startup path.
2. QA should run the Stage-2 benchmark closure packet next and attach fresh Scenario A/B + threshold + logcat evidence under `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...`.
