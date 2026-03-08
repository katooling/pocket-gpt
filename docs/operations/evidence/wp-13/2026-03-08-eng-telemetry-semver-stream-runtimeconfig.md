# WP-13 Engineering Evidence: Device Telemetry + Semantic Version Ordering + Stream Drain + Runtime Payload

Date: 2026-03-08  
Owner: Engineering

## Objective

Implement and validate four follow-up hardening tasks:

1. Replace synthetic send-path device state with real Android telemetry.
2. Replace lexical manifest version ordering with semantic-aware comparison.
3. Remove `ProcessCommandRunner` stdout/stderr deadlock risk.
4. Implement runtime side-load payload loading in `RuntimeConfig.resolvePayload`.

## Delivered Changes

1. Android telemetry-backed device state provider:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/controllers/AndroidTelemetryDeviceStateProvider.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModel.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/MainActivity.kt`
   - `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/controllers/AndroidTelemetryDeviceStateProviderTest.kt`
   - `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/ChatViewModelTest.kt`
2. Semantic-aware model version ordering:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDistributionManifestProvider.kt`
   - `apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDistributionManifestProviderTest.kt`
3. Concurrent process stream draining:
   - `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt`
   - `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/ProcessCommandRunnerTest.kt`
4. Runtime payload loading:
   - `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeConfig.kt`
   - `packages/app-runtime/src/commonTest/kotlin/com/pocketagent/runtime/RuntimeConfigEnvironmentTest.kt`

## Verification

Executed:

```bash
./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest :packages:native-bridge:test :packages:app-runtime:test
```

Result: PASS.
