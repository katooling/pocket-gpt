# Wave 4: Backend Identity Propagation

## Scope

- Owner: senior lead
- Goal: stop treating actual executed backend identity as invisible after generation.

## Landed

- Added shared backend-identity resolution in [RuntimeBridgeContracts.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt) using existing backend diagnostics payloads.
- Exposed the identity through [RuntimeInferencePorts.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeInferencePorts.kt) and [LlamaCppInferenceModule.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/LlamaCppInferenceModule.kt).
- Extended [RuntimeExecutionStats.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/packages/core-domain/src/commonMain/kotlin/com/pocketagent/core/RuntimeExecutionStats.kt) with `backendIdentity`.
- [SendMessageUseCase.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/SendMessageUseCase.kt) now records backend identity from the cache-aware runtime port.
- [RuntimeTuningStore.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt) now:
  - writes backend-aware envelopes on successful runs when backend evidence exists
  - prefers exact backend match first
  - falls back to legacy entries
  - accepts a unique backend-specific recommendation only when there is exactly one matching backend bucket for the same device/model/context envelope

## Evidence

- Shared seam test in [LlamaCppInferenceModuleTest.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/packages/native-bridge/src/commonTest/kotlin/com/pocketagent/nativebridge/LlamaCppInferenceModuleTest.kt) proves backend identity resolves from diagnostics.
- Runtime stats test in [SendMessageUseCaseTest.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/packages/app-runtime/src/commonTest/kotlin/com/pocketagent/runtime/SendMessageUseCaseTest.kt) proves `backendIdentity` is present in the returned `ChatResponse`.
- Verification passed:
  - `./gradlew :packages:native-bridge:test :packages:app-runtime:test`
  - `./gradlew :apps:mobile-android:compileDebugKotlin`

## Known Limit

- Failure-path tuning writes still do not carry backend identity because `RuntimeTuning.recordFailure(...)` is called without runtime stats or an executed-backend hint.
- This is documented rather than guessed. Full backend-aware failure demotion requires widening that call path.

## Safety Rule

- The tuning store intentionally refuses to guess when multiple backend-specific recommendation buckets exist for the same envelope and no exact backend hint is available.
