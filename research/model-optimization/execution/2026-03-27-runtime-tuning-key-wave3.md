# Wave 3: Runtime Tuning Key Separation

## Scope
- Lead: runtime-policy
- Goal: stop runtime tuning recommendations from colliding across materially different model artifacts and context sizes.

## Landed
- Introduced envelope-aware recommendation and history keys in [RuntimeTuningStore.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt).
- Key dimensions now include:
  - `modelVersion`
  - `quantClass`
  - `artifactIdentity`
  - `contextBucket`
  - `backendIdentity`
- Recommendation reads are backward-compatible:
  - read new envelope-aware key first
  - fall back to legacy key if no new recommendation exists
- Recommendation payload exports now include the envelope fields so diagnostics and history export stay self-describing.
- Sample exports now include `appliedSpeculativeDraftGpuLayers` and `appliedUseMmap`, which were previously omitted from stored history payloads.

## Evidence
- Pure helper coverage added in [RuntimeTuningDeciderTest.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/RuntimeTuningDeciderTest.kt):
  - storage keys diverge across version/artifact/context changes
  - quant-class inference prefers version, then path, then model id
  - artifact identity prefers SHA-256 and falls back to a stable path hash
- Android main source compiled successfully:
  - `./gradlew :apps:mobile-android:compileDebugKotlin`

## Known Limit
- `backendIdentity` remains `unknown` by design in this wave.
- Reason: `RuntimeTuningStore.recordSuccess` and `recordFailure` do not yet receive concrete executed-backend identity from runtime execution stats or config.
- This prevents safe separation of OpenCL vs Hexagon vs CPU observations at write time.
- Follow-up: widen the tuning write path with concrete backend identity once the runtime service exports that fact as evidence instead of inference.

## Verification Notes
- Attempted Android unit target:
  - `./gradlew :apps:mobile-android:testDebugUnitTest --tests com.pocketagent.android.runtime.RuntimeTuningDeciderTest --tests com.pocketagent.android.runtime.RuntimeDiagnosticsSnapshotParserTest --tests com.pocketagent.android.runtime.GpuOffloadQualificationTest`
- Result: blocked by unrelated unresolved references in [ChatScreenContractTest.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/ChatScreenContractTest.kt), outside this wave’s write scope.
