# WP-11 QA-08 UI Gate Rerun (Device)

Date: 2026-03-04  
Work package: WP-11  
Task: QA-08  
Device: `RR8NB087YTF` (`SM_A515F`, Android 13)  
Status: Done (gate criteria satisfied)

## Objective

Re-run WP-11 UI validation on physical device, close remaining `UI-01`..`UI-10` evidence gaps, and record deterministic artifacts.

## Commands Run and Outcomes

1. `./gradlew --no-daemon -Pkotlin.incremental=false :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-closeout-20260304-114817/01-testDebugUnitTest.log`

2. `env ANDROID_HOME=/Users/mkamar/Library/Android/sdk ANDROID_SDK_ROOT=/Users/mkamar/Library/Android/sdk ./gradlew --no-daemon -Pkotlin.incremental=false :apps:mobile-android:connectedDebugAndroidTest`
   - Outcome: PASS (`BUILD SUCCESSFUL`, 6 tests on physical device, 0 failed)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-closeout-20260304-114817/02-connectedDebugAndroidTest.log`

3. `export PATH="$PATH:$HOME/.maestro/bin" && env ANDROID_HOME=/Users/mkamar/Library/Android/sdk ANDROID_SDK_ROOT=/Users/mkamar/Library/Android/sdk python3 tools/devctl/main.py lane maestro`
   - Outcome: PASS (`scenario-a` + `scenario-b` completed)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-closeout-20260304-114817/03-maestro.log`

4. `for i in 1..10: maestro scenario-a + scenario-b` soak loop with logcat capture and app-specific crash/OOM/ANR scan
   - Outcome: PASS (`10/10` runs PASS, app-specific crash/OOM/ANR matches = `0/0/0`)
   - Artifacts:
     - `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-soak-20260304-113110/soak-summary.txt`
     - `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-soak-20260304-113110/app-crash-signature-matches.txt`
     - `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-soak-20260304-113110/app-oom-signature-matches.txt`
     - `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-soak-20260304-113110/app-anr-signature-matches.txt`

## UI Acceptance Matrix (`UI-01`..`UI-10`)

1. `UI-01` Launch renders chat screen/composer/empty-state: PASS
   - Evidence: instrumentation `launchShowsComposerAndOfflineIndicator`.
2. `UI-02` Send message streams and finalizes assistant bubble: PASS
   - Evidence: instrumentation `sendMessageShowsUserAndAssistantBubbles`; Maestro `scenario-a`.
3. `UI-03` Session switch preserves per-session timeline: PASS
   - Evidence: `ChatViewModelTest` (`session switch preserves per-session timeline state`).
4. `UI-04` App restart restores session list and last active session: PASS
   - Evidence: `ChatViewModelTest` (`bootstraps from persisted sessions and restores turns`).
5. `UI-05` Image attach flow success + invalid-file failure: PASS
   - Evidence: `ChatViewModelTest` (`attach image success...`, `attach image failure...`).
6. `UI-06` Tool request success + rejection error UX: PASS
   - Evidence: instrumentation `toolAndDiagnosticsActionsRenderResults`; `ChatViewModelTest` (`tool request success and failure...`).
7. `UI-07` Advanced sheet routing mode update reflected: PASS
   - Evidence: Maestro `scenario-b` (`QWEN_0_8B` select), `ChatViewModelTest` routing mode assertion.
8. `UI-08` Diagnostics export action + redaction safety: PASS
   - Evidence: instrumentation `toolAndDiagnosticsActionsRenderResults` (export action), `AndroidMvpContainerTest` diagnostics redaction assertions.
9. `UI-09` Offline policy remains enforced during UI actions: PASS
   - Evidence: runtime/policy regressions (`WP-04` QA-03 rerun), offline indicator in UI/instrumentation, no policy bypass introduced in rerun lanes.
10. `UI-10` Long-run UI soak (navigation + send/tool/advanced flows) no ANR/OOM/crash: PASS
   - Evidence: 10-run Maestro soak packet (`wp-11-ui-soak-20260304-113110`) with app-specific crash/OOM/ANR counts all zero.

## Gate Decision

1. WP-11 UI acceptance gate criteria are satisfied.
2. WP-11 is eligible for `Done` status from QA evidence perspective.
