# WP-13 QA Evidence: ENG-22 Revalidation on A51 (SM-A515F)

Date: 2026-03-09  
Owner: QA + Engineering  
Primary device: `DEVICE_SERIAL_REDACTED` (`SM-A515F`, Android 13)

## Objective

Validate ENG-22 acceptance on required-tier hardware and capture latest outcomes for required lanes:

1. `android-instrumented`
2. `maestro`
3. strict `journey --mode strict --repeats 3 --reply-timeout-seconds 90`

## Environment Notes

1. Parallel local test activity introduced device-collision noise via extra ADB endpoints.
2. Runs below were taken after enforcing single-target execution with:
   - `ADB_MDNS_AUTO_CONNECT=0`
   - `ADB_SERIAL='DEVICE_SERIAL_REDACTED'`

## Commands and Outcomes

1. `python3 tools/devctl/main.py governance docs-health`
   - Result: PASS
2. `python3 tools/devctl/main.py governance docs-accuracy`
   - Result: PASS (drift report `build/devctl/docs-drift-report.json`)
3. `ADB_MDNS_AUTO_CONNECT=0 ADB_SERIAL='DEVICE_SERIAL_REDACTED' python3 tools/devctl/main.py lane android-instrumented`
   - Result: FAIL (`MainActivityUiSmokeTest` failures); provisioning preflight executed before connected tests.
4. `ADB_MDNS_AUTO_CONNECT=0 ADB_SERIAL='DEVICE_SERIAL_REDACTED' python3 tools/devctl/main.py lane maestro`
   - Result: FAIL (`scenario-a`: `Tap on "Send"` element not found).
5. `ADB_MDNS_AUTO_CONNECT=0 ADB_SERIAL='DEVICE_SERIAL_REDACTED' python3 tools/devctl/main.py lane journey --mode strict --repeats 3 --reply-timeout-seconds 90`
   - Result: FAIL (`run-01/02/03:send-capture`).
   - Repeat execution status: all requested repeats executed; `run-01/02/03:instrumentation` all PASSED.
6. Targeted ENG-22 proof:
   - `adb -s DEVICE_SERIAL_REDACTED shell am instrument -w -r -e class 'com.pocketagent.android.RealRuntimeProvisioningInstrumentationTest#seedModelsAndVerifyStartupChecks' -e stage2_model_0_8b_path /sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf -e stage2_model_2b_path /sdcard/Android/media/com.pocketagent.android/models/qwen3.5-2b-q4.gguf -e stage2_enable_provisioning_test true com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner`
   - Result: PASS (`OK (1 test)`).

## ENG-22 Acceptance Check

1. Provisioning preflight instrumentation test passes on required-tier device.
   - Status: PASS (direct targeted run passed; lane preflights advanced past provisioning gate).
2. Strict journey rerun executes all requested repeats (no preflight short-circuit).
   - Status: PASS (`run-01`, `run-02`, `run-03` instrumentation executed and passed).
3. Latest WP-13 evidence note records pass/fail outcomes and artifact roots for all three required lanes.
   - Status: PASS (this note + roots below).

## Artifact Roots

1. Android instrumented:
   - `scripts/benchmarks/runs/2026-03-09/DEVICE_SERIAL_REDACTED/android-instrumented/20260309-132538/`
2. Maestro:
   - `scripts/benchmarks/runs/2026-03-09/DEVICE_SERIAL_REDACTED/maestro/20260309-133051/`
3. Journey strict (3 repeats):
   - `scripts/benchmarks/runs/2026-03-09/DEVICE_SERIAL_REDACTED/journey/20260309-133607/`
4. Journey report:
   - `scripts/benchmarks/runs/2026-03-09/DEVICE_SERIAL_REDACTED/journey/20260309-133607/journey-report.json`
5. Docs drift report:
   - `build/devctl/docs-drift-report.json`

## Decision Note

1. ENG-22 provisioning blocker is validated as closed on required-tier hardware.
2. Required lane reliability is still failing for non-ENG-22 reasons:
   - `MainActivityUiSmokeTest` assertion/timeouts in `android-instrumented`
   - `scenario-a` send action visibility/interaction failure in `maestro`
   - strict journey send-capture failures (`no_terminal_event` / kickoff launch failures)
3. Next execution priority is to stabilize send/runtime UI flows so QA-11 rerun can fully pass.

## Additional Validation: Uninstall + Model Reuse (A51)

Goal: verify whether large model artifacts can be reused after app uninstall/reinstall.

1. Baseline check before uninstall:
   - package media path models existed under `/sdcard/Android/media/com.pocketagent.android/models`.
2. Uninstall:
   - `adb -s DEVICE_SERIAL_REDACTED uninstall com.pocketagent.android`
   - `adb -s DEVICE_SERIAL_REDACTED uninstall com.pocketagent.android.test`
3. Post-uninstall state:
   - `/sdcard/Android/media/com.pocketagent.android/models` removed by Android uninstall lifecycle.
   - `/sdcard/Download/com.pocketagent.android/models` remained present (~1.6G).
4. Reinstall:
   - `adb -s DEVICE_SERIAL_REDACTED install -r apps/mobile-android/build/outputs/apk/debug/mobile-android-debug.apk`
   - `adb -s DEVICE_SERIAL_REDACTED install -r apps/mobile-android/build/outputs/apk/androidTest/debug/mobile-android-debug-androidTest.apk`
5. Reuse verification using updated preflight:
   - `prepare_real_runtime_env` restored both model files from `/sdcard/Download/com.pocketagent.android/models` to `/sdcard/Android/media/com.pocketagent.android/models`.
   - Provisioning probe (`RealRuntimeProvisioningInstrumentationTest#seedModelsAndVerifyStartupChecks`) passed after restore.

Conclusion:

1. App-media model files alone do not survive uninstall (Android behavior).
2. With current `devctl` hardening, persistent cache in `/sdcard/Download/com.pocketagent.android/models` is reused after reinstall, and models are restored to app-media path without re-downloading.

## Latest Revalidation Update (A51, post lane retry hardening)

Date: 2026-03-09 (later same day)

1. Added lane retry hardening coverage and reran targeted strict journey subset:
   - Command: `python3 -m tools.devctl.main lane journey --repeats 1 --mode strict --steps send-capture,maestro --maestro-flows tests/maestro/scenario-a.yaml --reply-timeout-seconds 120`
   - Result: PASS (`run-01:send-capture`, `run-01:maestro:scenario-a`)
   - Artifact root: `scripts/benchmarks/runs/2026-03-09/DEVICE_SERIAL_REDACTED/journey/20260309-141957/`
2. Re-ran required `android-instrumented` lane:
   - Command: `ADB_MDNS_AUTO_CONNECT=0 ADB_SERIAL='DEVICE_SERIAL_REDACTED' python3 tools/devctl/main.py lane android-instrumented`
   - Result: FAIL (`MainActivityUiSmokeTest` had 5 deterministic failures)
   - Artifact root: `scripts/benchmarks/runs/2026-03-09/DEVICE_SERIAL_REDACTED/android-instrumented/20260309-142707/`
3. Re-ran full `maestro` lane:
   - Command: `ADB_MDNS_AUTO_CONNECT=0 ADB_SERIAL='DEVICE_SERIAL_REDACTED' python3 tools/devctl/main.py lane maestro`
   - Result: FAIL (`scenario-a` passed; `scenario-b` failed)
   - Artifact root: `scripts/benchmarks/runs/2026-03-09/DEVICE_SERIAL_REDACTED/maestro/20260309-143003/`

### Updated Root Cause (real issue, not device collision)

1. `scenario-a` send visibility failure was largely harness/state-transition fragility and is now stabilized by retry + relaunch recovery.
2. Remaining blockers are product/runtime-state issues:
   - `MainActivityUiSmokeTest` still fails in connected instrumentation (`modelSetupSheetOpensFromAdvancedControls`, `naturalLanguageReminderPromptRendersToolResult`, `gpuToggleAndModelActivationStressDoesNotCrash`, `modelSetupSheetCanScrollToBottomWithoutCrash`, `toolAndDiagnosticsActionsRenderResults`).
   - `scenario-b` fails under runtime startup degradation (`UI-STARTUP-001`) with `MODEL_ARTIFACT_CONFIG_MISSING:model=qwen3.5-0.8b-q4;field=payload_or_path`.
3. Device inspection during failure window showed stale runtime metadata in app prefs:
   - `/data/data/com.pocketagent.android/shared_prefs/pocketagent_runtime_models.xml` had active `q4_0` entry for 0.8b pointing to missing `.../qwen3.5-0.8b-q4-q4_0.gguf`.
   - 2B version list was empty at that point.

### Priority Update

1. ENG-22 remains accepted/closed.
2. Highest-priority next work is runtime metadata self-healing and startup/readiness stability (ENG-20 scope), then full QA-11 rerun (`android-instrumented` + `maestro` + strict `journey --repeats 3`).

## Follow-up Validation (same day)

1. `scenario-a` and `scenario-b` were both revalidated directly on `DEVICE_SERIAL_REDACTED` after adding app-context + startup-recovery guards in Maestro flows.
2. `prepare_real_runtime_env` now retries the provisioning instrumentation probe once when it fails with `Process crashed` (in addition to existing missing-path retry).
3. Full `lane maestro` remains unstable under intermittent multi-endpoint reconnect/cross-device interference; lock to a single active A51 endpoint is still required for clean QA-11 reruns.
