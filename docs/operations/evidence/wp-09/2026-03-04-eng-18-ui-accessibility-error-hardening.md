# WP-09 ENG-18 Evidence: UI Accessibility + Error-State Hardening

Date: 2026-03-04  
Owner: Engineering (Android)  
Task: `ENG-18`  
Status: Done

## Objective

Harden WP-11 UI for accessibility, deterministic user-readable error handling, and streaming/list performance guardrails.

## Delivered Scope

1. Added normalized UI error contract (`UiError`, `UiErrorMapper`) with stable support codes:
   - `UI-STARTUP-001`
   - `UI-IMG-VAL-001`
   - `UI-TOOL-SCHEMA-001`
   - `UI-RUNTIME-001`
2. Extended runtime UI state with normalized error fields (`lastErrorCode`, `lastErrorUserMessage`, `lastErrorTechnicalDetail`) while preserving backward-compatible `lastError`.
3. Added ViewModel boundary mapping for:
   - `IMAGE_VALIDATION_ERROR:*`
   - `TOOL_VALIDATION_ERROR:*`
4. Added accessibility labels/semantics and string resources for key controls.
5. Reduced persistence churn during token streaming by persisting on major state transitions instead of every token update.
6. Added stability hardening so missing `adb` command does not crash the app startup path in fallback runtime checks.

## Code References

1. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state/ChatUiState.kt`
2. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state/UiErrorMapper.kt`
3. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModel.kt`
4. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt`
5. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AdbDeviceLlamaCppRuntimeBridge.kt`
6. `apps/mobile-android/src/main/res/values/strings.xml`

## Validation Commands and Outcomes

Run root:

- `scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/eng-18-qa-10-ui-hardening-20260304-131620/`

Commands:

1. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS
   - Artifact: `scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/eng-18-qa-10-ui-hardening-20260304-131620/01-testDebugUnitTest.log`
2. `python3 tools/devctl/main.py lane android-instrumented`
   - Outcome: PASS (6 tests on `SM-A515F - 13`)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/eng-18-qa-10-ui-hardening-20260304-131620/02-android-instrumented.log`
3. `python3 tools/devctl/main.py lane maestro`
   - Outcome: PASS (`scenario-a`, `scenario-b`)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/eng-18-qa-10-ui-hardening-20260304-131620/03-maestro.log`

## Acceptance Mapping

1. Accessibility semantics for primary controls: complete.
2. User-readable deterministic error UX for startup/image/tool-schema failures: complete.
3. Streaming/list guardrails and non-jank regression proxy test updates: complete.
4. Required unit + instrumentation + maestro lanes: complete.
