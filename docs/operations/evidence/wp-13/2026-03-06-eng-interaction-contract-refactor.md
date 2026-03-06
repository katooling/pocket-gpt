# ENG Interaction Contract Refactor (OpenAI-Style Core)

Date: 2026-03-06  
Owner: Engineering

## Summary

Implemented big-bang interaction architecture refactor for runtime/chat contracts:

1. Added canonical typed interaction schema (messages/parts/tool calls metadata).
2. Replaced runtime prompt assembly usage from legacy `buildPromptContext()` strings to template-rendered prompts with strict model profile registry.
3. Added model template availability gate (`TEMPLATE_UNAVAILABLE`) into startup/runtime path.
4. Extracted runtime modules:
   - `InteractionPlanner`
   - `ChatTemplateRenderer`
   - `InferenceExecutor`
   - `ToolLoopCoordinator`
5. Added stream reducer (`StreamStateReducer`) to centralize terminal-event invariant and timeout/cancel/failure mapping in UI flow.
6. Upgraded session persistence format to store typed interaction payloads with backward decode support for existing saved state.

## UX/Policy Outcomes

1. Removes role-echo prone legacy prompt concatenation path from runtime send flow.
2. Keeps manual model activation policy unchanged.
3. Hard-fails missing template contract with deterministic startup guidance instead of silent fallback.
4. Keeps offline/local-first posture unchanged.

## Verification

Executed:

```bash
./gradlew --no-daemon :packages:app-runtime:test :packages:core-domain:test :apps:mobile-android:testDebugUnitTest
```

Result: PASS.

Notes:

1. Device lanes (`android-instrumented`, `maestro`, `journey`) were intentionally deferred per active execution instruction.

