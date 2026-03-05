# ENG-11A Runtime Truth Gate (WP-12)

Date: 2026-03-04
Owner: Engineering (Runtime)
Status: Done (phase A)

## Objective

Prevent closure-path startup validation from passing on `ADB_FALLBACK` runtime, and surface runtime backend identity directly in stage output.

## Scope Delivered

1. Added explicit runtime backend contract (`NATIVE_JNI`, `ADB_FALLBACK`, `UNAVAILABLE`) in the runtime bridge layer.
2. Wired backend reporting into `LlamaCppInferenceModule` and startup checks through the runtime orchestration layer.
3. Enforced startup-check failure when backend is not `NATIVE_JNI` (default behavior).
4. Enforced non-zero process exit on blocking startup checks so closure lanes fail hard instead of only logging warnings.
5. Added local-scaffolding override env (`POCKETGPT_REQUIRE_NATIVE_RUNTIME_STARTUP=0`) to keep non-closure smoke lanes usable.
6. Updated resilience-guard signatures so runtime backend fallback/unavailable checks are classified as blocking.
7. Expanded unit tests for runtime backend reporting and startup-check enforcement behavior.

## Commands Run and Outcomes

1. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS
   - Notes: includes updated runtime bridge/inference/container/resilience tests.

2. `bash scripts/dev/test.sh quick`
   - Outcome: PASS
   - Notes: devctl tests + package tests + host/app unit lanes green.

3. `POCKETGPT_QWEN_3_5_0_8B_Q4_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa POCKETGPT_QWEN_3_5_2B_Q4_SHA256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ./gradlew --no-daemon :apps:mobile-android-host:run`
   - Outcome: FAIL (expected for fallback backend; task exits non-zero)
   - Runtime behavior outcome: startup checks fail fast on fallback backend and terminate lane with message:
     - `Runtime backend: ADB_FALLBACK`
     - `Startup checks failed: Runtime backend is ADB_FALLBACK... Native JNI runtime is required for closure-path startup checks...`

## Files Updated

1. `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt`
2. `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/AdbDeviceLlamaCppBridge.kt`
3. `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/LlamaCppInferenceModule.kt`
4. `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeOrchestrator.kt`
5. `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/StageRunnerMain.kt`
6. `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ResilienceGuards.kt`
7. `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridgeTest.kt`
8. `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/AdbDeviceLlamaCppBridgeTest.kt`
9. `packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/LlamaCppInferenceModuleTest.kt`
10. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/AndroidMvpContainerTest.kt`
11. `packages/app-runtime/src/commonTest/kotlin/com/pocketagent/runtime/ResilienceGuardsTest.kt`

## Result

ENG-11A phase-A acceptance met: closure-path startup checks no longer treat ADB fallback as acceptable runtime backend by default.

## Remaining ENG-11 Follow-ons

1. Replace fallback-backed closure runs with proven native JNI runtime runs on Android ARM.
2. Attach device-side native inference evidence packet with measured first-token/decode metrics from real model execution.
