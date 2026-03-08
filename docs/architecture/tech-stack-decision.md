# Tech Stack Decision

Last updated: 2026-03-08

## Decision Summary

1. Baseline runtime: `llama.cpp` via native JNI bridge on Android.
2. Packaging/runtime optimization: optional Vulkan GPU offload in packaged runtime when device/backend support exists.
3. Shared architecture: Kotlin packages + Compose Android app shell.
4. Runtime orchestration: `packages/app-runtime` composition/use-case layer.
5. Tool execution: strict local allowlist + schema validation.
6. Storage: local persistence for sessions/memory/model metadata.

## Why This Stack

1. Delivers local-first behavior now with production runtime path (`NATIVE_JNI`) and deterministic fallback labeling.
2. Keeps platform-specific performance controls available (profile + GPU capability gating) without changing product contracts.
3. Preserves testability through package boundaries and host/JVM lanes.

## Alternatives and Current Position

1. Core ML iOS runtime remains a future parity track; not part of current shipped Android path.
2. LiteRT/NNAPI-first approach is no longer the active Android baseline; current baseline is JNI + `llama.cpp`.
3. Hosted/cloud inference default path remains out of scope for MVP claims.

## Non-Negotiables

1. Core user flows must run without cloud dependency.
2. Privacy claims must match enforceable policy/runtime behavior.
3. Tool execution must remain bounded and deterministic.
4. Runtime startup/send failures must surface deterministic, recoverable UX states.

## Maturity Snapshot (As Of 2026-03-08)

| Area | Decision | Maturity |
|---|---|---|
| Android baseline runtime | `llama.cpp` + JNI bridge | Implemented |
| Android GPU path | Vulkan offload (capability-gated) | Implemented (device/backend dependent) |
| Runtime orchestration layer | `packages/app-runtime` use-case composition | Implemented |
| Tool runtime safety | schema-validated allowlist | Implemented |
| Memory persistence | local persisted module + retrieval | Implemented |
| iOS runtime parity | Core ML + Metal track | Planned |
| Voice layer (STT/TTS) | offline-first, policy-gated | Planned |
