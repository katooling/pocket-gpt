# Wave 5: Backend-Aware Failure Tuning

## Scope

- Owner: senior lead
- Goal: close the remaining backend-aware failure-path tuning seam from wave 4.

## Landed

- [RuntimeTuningStore.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt) now accepts an optional `backendIdentityHint` on `recordFailure(...)`.
- Failure-path recommendation lookup now uses:
  - exact backend-aware key when a backend hint exists
  - unique backend-specific match when unambiguous
  - legacy fallback when needed
- [ChatViewModelSendWorkflow.kt](/Users/mkamar/Non_Work/Projects/ai/pocket-gpt/apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModelSendWorkflow.kt) now supplies backend identity on failure by preferring a fresh runtime diagnostics snapshot and falling back to the last known active backend in UI state.

## Evidence

- Verification passed:
  - `./gradlew :apps:mobile-android:compileDebugKotlin`

## Known Limit

- Failure handling still depends on backend evidence being available at or immediately after failure time.
- If diagnostics are unavailable, the path falls back to the last known active backend from UI state rather than inventing a new backend identity.
- Android unit tests remain blocked at the module level by the unrelated `ChatScreenContractTest.kt` compile issue, so this wave was compile-verified rather than unit-verified in the Android module.
