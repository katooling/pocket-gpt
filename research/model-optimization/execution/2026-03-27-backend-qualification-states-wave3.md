# Wave 3: Backend Qualification State Normalization

## Scope
- Lead: native-runtime
- Goal: make GPU/backend qualification diagnostics canonical and machine-parseable instead of relying on ad-hoc strings.

## Landed
- Added normalized backend qualification and feature qualification enums in [RuntimeDiagnosticsSnapshot.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeDiagnosticsSnapshot.kt).
- Extended parsed diagnostics with:
  - `compiledBackends`
  - `discoveredBackends`
  - `backendCapabilities`
  - `backendQualificationState`
  - `flashAttnQualificationState`
  - `quantizedKvQualificationState`
- Updated [GpuOffloadQualification.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/GpuOffloadQualification.kt) so exported probe lines now emit:
  - `qualification_state`
  - `compiled_backends`
  - `discovered_backends`
  - `active_backend`
  - `flash_attn_feature_state`
  - `quantized_kv_feature_state`
- Widened `NativeBackendDiagnostics` to carry the backend fields used by the qualifier, including device counts and guard reasons, so the diagnostics path is internally consistent.

## Evidence
- Parser coverage added in [RuntimeDiagnosticsSnapshotParserTest.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/RuntimeDiagnosticsSnapshotParserTest.kt):
  - qualified OpenCL case with guarded feature states
  - runtime-unsupported CPU fallback case with unavailable feature states
- Qualifier coverage added in [GpuOffloadQualificationTest.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/GpuOffloadQualificationTest.kt):
  - canonical diagnostics line fields are emitted after qualification
  - runtime-unsupported state is emitted explicitly
- Android main source compiled successfully:
  - `./gradlew :apps:mobile-android:compileDebugKotlin`

## Known Limit
- Capability normalization is still bounded by what the native diagnostics payload exposes today.
- There is not yet a deeper per-feature support matrix for each backend, so feature qualification remains derived from active backend, guard reasons, and runtime/probe state.

## Verification Notes
- Attempted Android unit target:
  - `./gradlew :apps:mobile-android:testDebugUnitTest --tests com.pocketagent.android.runtime.RuntimeTuningDeciderTest --tests com.pocketagent.android.runtime.RuntimeDiagnosticsSnapshotParserTest --tests com.pocketagent.android.runtime.GpuOffloadQualificationTest`
- Result: blocked by unrelated unresolved references in [ChatScreenContractTest.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/ChatScreenContractTest.kt), outside this wave’s write scope.
