# WP-13 Engineering Evidence: Chat Layout Hardening

Date: 2026-03-05
Owner: Engineering
Device: RR8NB087YTF (SM-A515F, Android 13)

## Objective

Investigate and harden the mobile chat viewport issue where messages appeared cut off or limited to a reduced visible area.

## Changes Applied

1. Hardened chat body sizing and status-header layout in Compose:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatScreen.kt`
   - Header chips now use wrapping layout and constrained chip label rendering.
   - Chat body enforces full-size container behavior before weighted list allocation.
2. Added viewport regression assertion:
   - `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/MainActivityUiSmokeTest.kt`
   - `chatMessageListOccupiesMajorityOfViewportWhenRuntimeReady` verifies chat list height ratio.
3. Removed shared-device runner hackiness by codifying exclusive device locks in lane execution:
   - `tools/devctl/lanes.py`
   - `android-instrumented`, `maestro`, `journey`, and `device` lanes now acquire per-serial locks.
4. Added explicit device-health preflight for mobile lanes:
   - wake/unlock signal, `/data` utilization guard, runtime-media path write probe, package owner metadata check.
5. Added run-owner metadata in journey report payload + summary:
   - `run_owner` and `run_host` fields in `journey-report.json` and `journey-summary.md`.
6. Added lock/preflight/metadata tests + operator docs:
   - `tools/devctl/tests/test_lanes.py`
   - `scripts/dev/README.md`
   - `docs/testing/android-dx-and-test-playbook.md`

## Validation

1. Kotlin compile checks:
   - `./gradlew --no-daemon :apps:mobile-android:compileStandardDebugKotlin :apps:mobile-android:compileStandardDebugAndroidTestKotlin`
2. Devctl tests:
   - `python3 -m unittest discover -s tools/devctl/tests`
   - includes lock behavior tests:
     - `test_device_lock_is_reentrant_for_same_process`
     - `test_device_lock_times_out_when_held_by_another_process`
   - includes device-health and report metadata tests:
     - `test_run_device_health_preflight_happy_path`
     - `test_write_journey_report_includes_owner_metadata`
3. Governance:
   - `python3 tools/devctl/main.py governance docs-drift`
4. Device install:
   - `./gradlew --no-daemon :apps:mobile-android:installStandardDebug`

## Runtime Notes

Earlier same-day attempts were interrupted by shared-device uninstall churn (`deletePackageX`).  
Final lock-safe wireless reruns (with per-device lock + run-owner metadata) passed for all gate lanes:

1. Android instrumented:
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/android-instrumented/20260305-152034/`
2. Maestro:
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-153028/`
3. Journey (instrumentation + Scenario A/B/C with screenshots and summary):
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/journey/20260305-153850/`

## Raw Artifacts

`scripts/benchmarks/runs/2026-03-05/RR8NB087YTF/journey/20260305-144030-chat-layout-hardening/`

Contained files:

1. `before-fix-snapshot.png`
2. `after-patch-onboarding.png`
3. `keyboard-state.png`
4. `instrumentation-failure-logcat.txt`
5. `instrumentation-result.xml`
6. `session-summary.md`
7. `after-patch-runtime-ready.png`
