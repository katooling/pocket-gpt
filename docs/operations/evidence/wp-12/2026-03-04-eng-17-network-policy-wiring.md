# ENG-17 Platform Network Policy Wiring + Regression Checks (WP-12)

Date: 2026-03-04
Owner: Engineering (Security)
Support: Runtime
Lifecycle: Done

## Objective

Wire policy decisions to Android platform network posture and verify runtime behavior reflects policy outcomes (not policy checks alone).

## Scope Delivered

1. Added policy-aware network client in the runtime layer for startup posture checks and action-level enforcement decisions.
2. Integrated network startup checks and runtime probe enforcement into startup-check flow.
3. Hardened manifest posture:
   - cleartext traffic disabled
   - explicit network security config wiring
4. Added regression tests for policy enforcement behavior and manifest/network-config posture.

## Code + Test Delta

1. `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PolicyAwareNetworkClient.kt`
2. `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeOrchestrator.kt`
3. `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/ResilienceGuards.kt`
4. `apps/mobile-android/src/main/AndroidManifest.xml`
5. `apps/mobile-android/src/main/res/xml/network_security_config.xml`
6. `packages/app-runtime/src/commonTest/kotlin/com/pocketagent/runtime/PolicyAwareNetworkClientTest.kt`
7. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/NetworkPolicyManifestConfigTest.kt`

## Commands Run and Outcomes

1. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS
2. `python3 tools/devctl/main.py lane android-instrumented`
   - Outcome: PASS
3. `bash scripts/dev/test.sh quick`
   - Outcome: PASS

## Acceptance Criteria Status

1. Offline-only mode cannot perform disallowed network actions: MET.
2. Policy result is wired into runtime behavior and startup checks: MET.
3. Tests/evidence map to offline privacy guarantees: MET.
