# WP-11 QA-08 UI Gate Rerun (Device)

Date: 2026-03-04  
Work package: WP-11  
Task: QA-08  
Device: `RR8NB087YTF` (`SM_A515F`, Android 13)  
Status: In Progress (partial gate pass; closure still blocked)

## Objective

Re-run WP-11 UI validation on physical device after instrumentation fix and publish explicit `UI-01..UI-10` evidence map.

## Commands Run and Outcomes

1. `env ANDROID_HOME=/Users/mkamar/Library/Android/sdk ANDROID_SDK_ROOT=/Users/mkamar/Library/Android/sdk ./gradlew --no-daemon -Pkotlin.incremental=false :apps:mobile-android:connectedDebugAndroidTest`
   - Outcome: PASS (`BUILD SUCCESSFUL`, 5 tests finished on device)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-gate-20260304-111118/01-connectedDebugAndroidTest.log`

2. `export PATH="$PATH:$HOME/.maestro/bin" && env ANDROID_HOME=/Users/mkamar/Library/Android/sdk ANDROID_SDK_ROOT=/Users/mkamar/Library/Android/sdk python3 tools/devctl/main.py lane maestro`
   - Outcome: PASS (`scenario-a` + `scenario-b` completed)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-gate-20260304-111118/02-maestro.log`

3. `./gradlew --no-daemon -Pkotlin.incremental=false :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-gate-20260304-111118/03-testDebugUnitTest.log`

4. `adb -s RR8NB087YTF logcat -d > scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-gate-20260304-111118/04-logcat-after-ui-gate.txt`
   - Outcome: PASS (log capture complete; 107721 lines)
   - Artifact: `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/wp-11-ui-gate-20260304-111118/04-logcat-after-ui-gate.txt`

## UI Acceptance Matrix (`UI-01`..`UI-10`)

1. `UI-01` Launch renders chat screen/composer/empty-state: PASS
   - Evidence: instrumentation smoke `launchShowsComposerAndOfflineIndicator`.
2. `UI-02` Send message streams and finalizes assistant bubble: PASS
   - Evidence: instrumentation `sendMessageShowsUserAndAssistantBubbles`, Maestro `scenario-a`.
3. `UI-03` Session switch preserves per-session timeline: PARTIAL
   - Evidence: session UI exists; no dedicated device assertion run in this rerun.
4. `UI-04` App restart restores session list and last active session: PARTIAL
   - Evidence: `ChatViewModelTest` persisted bootstrap/restore path PASS; no dedicated device restart assertion run in this rerun.
5. `UI-05` Image attach success + invalid-file failures: NOT YET EVIDENCED
   - Evidence gap: no device-run image picker assertions in current Maestro/instrumented suite.
6. `UI-06` Tool request success + schema-rejection UX: NOT YET EVIDENCED
   - Evidence gap: runtime/tool safety coverage exists in container tests, but no UI-level device assertion in this rerun.
7. `UI-07` Advanced sheet routing mode update reflected: PASS
   - Evidence: Maestro `scenario-b` (`Advanced` -> `QWEN_0_8B`).
8. `UI-08` Diagnostics export action and redaction behavior: PARTIAL
   - Evidence: Maestro triggers export; diagnostics redaction validated in unit test (`AndroidMvpContainerTest`).
9. `UI-09` Offline policy remains enforced during UI actions: PARTIAL
   - Evidence: policy/runtime suite exists (`WP-04`), but no dedicated UI-lane network-policy assertion in this rerun.
10. `UI-10` Long-run UI soak (navigation + send/image/tool loops) no ANR/OOM: NOT YET EVIDENCED
   - Evidence gap: Stage-6 soak exists for runtime hardening; no explicit WP-11 UI loop soak packet yet.

## Gate Decision

1. WP-11 remains `In Progress`.
2. Device instrumentation and Maestro lanes are now green after test fix.
3. Closure is blocked until remaining `UI-03/04/05/06/08/09/10` evidence is completed with device-oriented acceptance artifacts.

