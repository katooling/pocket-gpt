# WP-13 Engineering Evidence: Session ID Safety + Provenance Strict Mode + Chat Flow Split

Date: 2026-03-08  
Owner: Engineering

## Objective

Close three high-risk implementation items:

1. Prevent in-memory session ID collisions after delete/recreate and restore.
2. Enforce provenance policy when configured, with strict verification and safe non-crashing behavior.
3. Reduce `ChatViewModel` change-risk by extracting focused send/startup/persistence flows.

## Delivered Changes

1. Session ID generation hardening:
   - `packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/InMemoryConversationModule.kt`
   - `packages/core-domain/src/commonTest/kotlin/com/pocketagent/core/InMemoryConversationModuleTest.kt`
2. Provenance strict policy wiring:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelManagerTypes.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDistributionManifestProvider.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDownloadManager.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDownloadWorker.kt`
   - `apps/mobile-android/src/main/assets/model-distribution-catalog.json`
   - `apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDistributionManifestProviderTest.kt`
   - `apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/modelmanager/DownloadTaskStateTest.kt`
3. Chat orchestration refactor:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModel.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/ChatSendFlow.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/ChatStartupFlow.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/ChatPersistenceFlow.kt`

## Verification

Executed:

```bash
./gradlew --no-daemon :packages:core-domain:test
./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest
```

Result: PASS.

## Plan Closure Notes

1. Session collision overwrite risk is closed by monotonic counter + restore-aware counter advancement + regression coverage.
2. Provenance checks are now enforceable through manifest policy (`PROVENANCE_STRICT`) and strict verification failure does not crash runtime flows.
3. `ChatViewModel` responsibility split is completed for send/startup/persistence paths, while preserving existing behavior contracts and passing tests.
