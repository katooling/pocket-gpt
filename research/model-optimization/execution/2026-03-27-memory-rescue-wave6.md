# Wave 6: Memory Rescue Before Load Blocking

Date: 2026-03-27

## Scope

- Owner: Senior Lead integrating the remaining runtime-policy gap
- Supporting lead lanes:
  - Runtime Policy Lead: rescue-order design and planner semantics
  - Benchmarking and Validation Lead: proof that the rescue path is exercised before a hard block

## Problem

After waves 1-5, Pocket GPT still had one material in-repo optimization gap: the planner reduced context and applied GPU ceilings, but it could still block model load before trying cheaper runtime-parameter reductions such as disabling speculative decoding or shrinking micro-batch size.

That created an avoidable failure mode on constrained devices: a request could be rejected even though a smaller batch envelope would have run safely.

## Definition Of Done

- `RuntimePlanResolver` must attempt additional safe rescue steps before returning `loadBlockedReason`.
- Rescue behavior must be covered at the planner level and at the send/use-case level.
- The rescue path must leave diagnostics showing which layer was used.
- Existing package suites and Android compile must still pass.

## Landed Changes

- Added a memory rescue ladder in `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimePlanResolver.kt`.
- The planner now:
  - re-estimates memory through a shared helper for each candidate config
  - disables speculative decoding and clears draft GPU layers before blocking when speculation is active
  - tries progressively smaller `nBatch` / `nUbatch` candidates before declaring the model unrunnable
  - records `layer=memory_speculative_rescue` and `layer=memory_batch_rescue` diagnostics when those paths are exercised
- Added regression coverage in:
  - `packages/app-runtime/src/commonTest/kotlin/com/pocketagent/runtime/RuntimePlanResolverTest.kt`
  - `packages/app-runtime/src/commonTest/kotlin/com/pocketagent/runtime/SendMessageUseCaseTest.kt`

## Verification

- Passed: `./gradlew :packages:app-runtime:test --tests com.pocketagent.runtime.RuntimePlanResolverTest --tests com.pocketagent.runtime.SendMessageUseCaseTest`
- Passed: `./gradlew :packages:native-bridge:test :packages:app-runtime:test`
- Passed: `./gradlew :apps:mobile-android:compileDebugKotlin`

## Result

The repo-side optimization backlog from the original master plan is now closed. Remaining work is no longer a missing-code problem; it is an evidence, qualification, and rollout problem documented in `future-work-plan.md`.
