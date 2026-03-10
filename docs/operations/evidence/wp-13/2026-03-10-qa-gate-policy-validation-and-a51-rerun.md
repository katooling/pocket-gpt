# WP-13 QA Evidence: Gate Policy Validation + A51 Rerun (Merge-Unblock/Promotion)

Date: 2026-03-10
Owner: QA + Engineering
Primary device: `192.168.1.43:40281` (`SM-A515F`, Android 13)

## Objective

Validate the new gate structure and evidence model:

1. `devctl gate merge-unblock` (merge + doctor + android-instrumented + risk-triggered lifecycle)
2. `devctl gate promotion` (merge + doctor + android-instrumented + maestro + strict journey + optional screenshot-pack)
3. Runtime-vs-correctness report output for gate decisions (product-signal vs harness-noise)

## Implemented in this run

1. Added `devctl gate merge-unblock` / `devctl gate promotion` wrapper support and JSON gate reports under `build/devctl/gates/`.
2. Added per-step runtime/correctness classification fields (`duration_seconds`, `correctness`, `blocking`) for gate decisions.
3. Split Maestro onboarding coverage from post-onboarding scenario A/B/C:
   - new flow: `tests/maestro/scenario-onboarding.yaml`
   - scenario A/B/C now assert post-onboarding baseline.
4. Stabilized QA-13 send-capture kickoff reporting to always emit required fields (`phase`, `runtime_status`, `backend`, `active_model_id`, `placeholder_visible`) even on kickoff failure.
5. Reworked screenshot-pack path:
   - deterministic instrumentation subset first
   - optional full instrumentation expansion only if inventory still missing
   - `--product-signal-only` mode supported.

## Build/Test execution and outcomes

1. `bash scripts/dev/test.sh merge`
   - Result: PASS
2. `./gradlew --no-daemon :apps:mobile-android:compileDebugKotlin`
   - Result: PASS
3. `./gradlew --no-daemon :apps:mobile-android:assembleDebugAndroidTest`
   - Result: PASS
4. `python3 -m unittest discover -s tools/devctl/tests`
   - Result: PASS
5. `python3 tools/devctl/main.py gate promotion --serial 192.168.1.43:40281 --include-screenshot-pack`
   - Result: FAIL
   - Report: `scripts/benchmarks/runs/2026-03-10/192.168.1.43:40281/gates/promotion-report.json`
6. `python3 tools/devctl/main.py gate merge-unblock --serial 192.168.1.43:40281`
   - Result: FAIL (`android-instrumented`, `lifecycle-e2e-first-run`)
   - Report: `build/devctl/gates/merge-unblock-20260310-094203.json` (transient build folder report)
7. `python3 tools/devctl/main.py gate merge-unblock --serial 192.168.1.43:40281 --skip-lifecycle`
   - Result: FAIL (`android-instrumented`)
   - Reports:
     - `build/devctl/gates/merge-unblock-20260310-095211.json` (transient build folder report)
     - `scripts/benchmarks/runs/2026-03-10/192.168.1.43:40281/gates/merge-unblock-report.json` (retained)

## Blocker classification (today)

### Product-signal blocker (blocking)

1. Real-runtime provisioning instrumentation crashes the app process:
   - Failing test: `RealRuntimeProvisioningInstrumentationTest#seedModelsAndVerifyStartupChecks`
   - Gate reason (latest): `DEVICE_ERROR: Instrumentation failed for ...seedModelsAndVerifyStartupChecks (reported failure: Process crashed.).`
2. Logcat shows native crash in runtime load path:
   - `Fatal signal 4 (SIGILL)` in app process
   - stack includes `libpocket_llama.so` and `...nativeLoadModel...`
3. Evidence artifact:
   - `scripts/benchmarks/runs/2026-03-10/192.168.1.43:40281/provisioning-debug/20260310-094554/logcat-crash.txt`

### Harness-noise classification

1. No harness-noise caveat was selected in final gate results.
2. Failures remained product-signal class because they stem from reproducible native runtime crash in app process preflight.

## Why downstream lanes fail

1. `android-instrumented`, `maestro`, `journey`, and `screenshot-pack` all depend on successful real-runtime provisioning preflight in current lane contract.
2. With provisioning crash unresolved, downstream lane pass cannot be claimed as valid release evidence.

## Criteria/unblock decision

1. Merge/promotion criteria are not “too harsh” for this run; they are correctly blocked by a product-signal crash in core runtime startup path.
2. Gate architecture now supports caveat/non-blocking treatment for selected harness-noise classes, but this incident does not qualify as harness noise.

## Recommended follow-up (next execution)

1. Prioritize native SIGILL root cause in `libpocket_llama.so` model-load path, then rerun:
   - `python3 tools/devctl/main.py gate merge-unblock`
   - `python3 tools/devctl/main.py gate promotion --include-screenshot-pack`
2. After provisioning stability, verify screenshot-pack deterministic path and journey QA-13 fields on real lane artifacts.
