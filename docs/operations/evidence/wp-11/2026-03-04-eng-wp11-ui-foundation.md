# WP-11 Evidence: ENG-10 UI Foundation + Product Recovery Alignment

Date: 2026-03-04  
Work package: WP-11  
Owner: Engineering + Product  
Status: In Progress (foundation delivered; QA UI acceptance still pending)

## Scope Delivered

1. Replaced placeholder `MainActivity` runtime shell with Compose-based chat-first UI.
2. Added app-facing runtime façade contract and stream lifecycle events.
3. Added routing-mode control contract (`AUTO`, `QWEN_0_8B`, `QWEN_2B`).
4. Added ViewModel/UI state model and session persistence for restart continuity.
5. Added image attach action, tool action UX, advanced controls sheet, and diagnostics export action.
6. Added tests for runtime/UI wiring and session restore behavior.
7. Updated ops/product/testing docs for dual-track release gate (`WP-07` + `WP-11`).

## Code References

1. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/MainActivity.kt`
2. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AndroidMvpContainer.kt`
3. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/RoutingMode.kt`
4. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/runtime/MvpRuntimeFacade.kt`
5. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModel.kt`
6. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt`
7. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state/ChatUiState.kt`
8. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state/SessionPersistence.kt`
9. `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/ConversationContracts.kt`
10. `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/InMemoryConversationModule.kt`

## Test/Validation Commands and Outcomes

1. `env ORG_GRADLE_PROJECT_kotlin.incremental=false bash scripts/dev/test.sh quick`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `scripts/benchmarks/runs/2026-03-04/ci-host/wp-11-ui-foundation/01-test-quick.log`

2. `./gradlew --no-daemon -Pkotlin.incremental=false :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `scripts/benchmarks/runs/2026-03-04/ci-host/wp-11-ui-foundation/02-mobile-android-unit.log`

3. `./gradlew --no-daemon -Pkotlin.incremental=false :apps:mobile-android:compileDebugAndroidTestKotlin`
   - Outcome: PASS (`BUILD SUCCESSFUL`)
   - Log: `scripts/benchmarks/runs/2026-03-04/ci-host/wp-11-ui-foundation/03-compile-androidTest.log`

## Raw Artifact Paths

1. `scripts/benchmarks/runs/2026-03-04/ci-host/wp-11-ui-foundation/01-test-quick.log`
2. `scripts/benchmarks/runs/2026-03-04/ci-host/wp-11-ui-foundation/02-mobile-android-unit.log`
3. `scripts/benchmarks/runs/2026-03-04/ci-host/wp-11-ui-foundation/03-compile-androidTest.log`

## Acceptance Mapping (Current)

Delivered now:

1. UI shell exists and supports chat/session/image/tool/advanced-control workflows.
2. Runtime façade and routing control contracts are active in app layer.
3. Session persistence + restore continuity path is implemented.
4. Runtime wiring tests include routing override + restored session prompt continuity.

Still pending for WP-11 close:

1. QA UI acceptance run (`UI-01`..`UI-10`) on connected device.
2. Maestro/instrumentation evidence packet under `docs/operations/evidence/wp-11/`.
3. Final Product/QA/Engineering gate signoff for WP-11 closure.

## Notes

1. The host CI quick lane required `ORG_GRADLE_PROJECT_kotlin.incremental=false` in this environment due transient Kotlin cache/daemon lock behavior.
2. This evidence note is foundation-level and is not the final WP-11 closure packet.
