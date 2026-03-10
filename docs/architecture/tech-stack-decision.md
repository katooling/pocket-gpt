# Tech Stack Decision

Last updated: 2026-03-10

## Decision Summary

1. Baseline runtime: `llama.cpp` via Android native bridge, with runtime mode selecting remote Android service (`remote`) or in-process JNI (`in_process`).
2. Packaging/runtime optimization: optional Vulkan GPU offload in packaged runtime when device/backend support exists.
3. Shared architecture: Kotlin packages + Compose Android app shell.
4. Runtime orchestration: `packages/app-runtime` composition/use-case layer.
5. Tool execution: strict local allowlist + schema validation.
6. Storage: local persistence for sessions/memory/model metadata.

## Why This Stack

1. Delivers local-first behavior now with production runtime path (`REMOTE_ANDROID_SERVICE` by default in non-debug) and deterministic backend labeling.
2. Keeps platform-specific performance controls available (profile + GPU capability gating) without changing product contracts.
3. Preserves testability through package boundaries and host/JVM lanes.

## Alternatives and Current Position

1. LiteRT/NNAPI-first approach is no longer the active Android baseline; current baseline is JNI + `llama.cpp`.
2. Hosted/cloud inference default path remains out of scope for MVP claims.

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
| Voice layer (STT/TTS) | offline-first, policy-gated | Planned |
