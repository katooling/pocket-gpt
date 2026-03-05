# WP-13 Engineering Evidence: P1 Model Manager Phase-2 + Recovery UX/UI

Date: 2026-03-05  
Owner: Engineering

## Objective

Close P1 implementation scope for:

1. `ENG-P1`: model manager phase-2 (downloads/progress/retry/storage/versioned installs/manual activation).
2. `UX-P1`: checksum/provenance/runtime-compatibility recovery copy refinement.
3. `UI-P1`: NotReady/Error CTA hierarchy and post-import/download/refresh/activate transition feedback.

## Delivered Changes

1. Flavor-gated download channel:
   - `apps/mobile-android/build.gradle.kts`
   - `apps/mobile-android/src/internalDownload/AndroidManifest.xml`
   - `apps/mobile-android/src/test/kotlin/com/pocketagent/android/NetworkPolicyManifestConfigTest.kt`
2. Versioned provisioning + activation/store controls:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/AndroidRuntimeProvisioningStore.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/AppDependencies.kt`
3. Download manager + worker lifecycle (queue/pause/resume/retry/verify/install-inactive):
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelManagerTypes.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDownloadTaskStateStore.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDownloadManager.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDownloadWorker.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/modelmanager/ModelDistributionManifestProvider.kt`
4. UI state + model manager surface:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state/ChatUiState.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ModelProvisioningSheet.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatApp.kt`
5. Runtime readiness/error UX and CTA hierarchy:
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatScreen.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModel.kt`
   - `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/state/UiErrorMapper.kt`
   - `apps/mobile-android/src/main/res/values/strings.xml`

## Test and Validation Evidence

Executed:

1. `./gradlew --no-daemon :apps:mobile-android:compileStandardDebugKotlin :apps:mobile-android:compileStandardDebugAndroidTestKotlin :apps:mobile-android:testDebugUnitTest`
2. `python3 -m unittest tools.devctl.tests.test_lanes -v`
3. `python3 tools/devctl/main.py lane android-instrumented`
4. `python3 tools/devctl/main.py lane maestro`

Results:

1. Compile + Android unit test layers passed.
2. `devctl` lane tests passed.
3. Device lanes (`android-instrumented`, `maestro`) are currently blocked in this workspace by missing connected `adb` device (`No adb devices detected`), not by build/test regressions.

## Additional Hardening Included

1. Real-runtime instrumentation suites now include explicit opt-in runner args to keep smoke lanes deterministic:
   - `stage2_enable_benchmark`
   - `stage2_enable_app_path_test`
   - `stage2_enable_provisioning_test`
   - `stage2_enable_journey_test`
2. Download resume path integrity was corrected to append partial bytes on HTTP range-resume in `ModelDownloadWorker`.

