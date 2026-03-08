# Modular Monolith Architecture

Last updated: 2026-03-08

## Why Modular Monolith

Current scope favors fast iteration with strict contracts in one repository/runtime process.

1. Shared tests and governance checks are easier to enforce.
2. Runtime/UI integration issues are diagnosed faster than in distributed service splits.
3. Package-level boundaries still allow future extraction if needed.

## Package Boundary Map

| Package/Module | Primary Responsibility | Key Public Contracts |
|---|---|---|
| `packages/core-domain` | sessions, turns, policy/observability interfaces | `ConversationModule`, `PolicyModule`, `ObservabilityModule`, `RoutingMode`, `SessionId` |
| `packages/inference-adapters` | inference/routing interfaces and model catalog contracts | `InferenceModule`, `RoutingModule`, `ModelCatalog`, `ModelArtifactManager` |
| `packages/tool-runtime` | safe local tool runtime and schema validation | `ToolModule`, `SafeLocalToolRuntime`, `ToolContracts` |
| `packages/memory` | persisted memory chunks and retrieval | `MemoryModule`, file/sqlite-backed implementations |
| `packages/native-bridge` | runtime backend wiring (JNI primary, ADB fallback) | `LlamaCppInferenceModule`, `NativeJniLlamaCppBridge`, runtime bridge contracts |
| `packages/app-runtime` | orchestration use cases and runtime composition root | `RuntimeOrchestrator`, `SendMessageUseCase`, `StartupChecksUseCase`, `MvpRuntimeFacade` |
| `apps/mobile-android` | Android UI/state/controllers and provisioning UX | `ChatViewModel`, controllers, Compose surfaces |
| `tools/devctl` | lane orchestration + governance automation | lane dispatcher, governance checks |

## Dependency Direction

1. UI/app modules depend on `app-runtime` contracts, not runtime internals.
2. `app-runtime` composes domain/inference/tool/memory/native-bridge packages.
3. `native-bridge` is the only package that speaks backend-specific runtime bridge details.
4. Policy enforcement remains centralized in runtime/domain contracts.
5. No package may read another package's private persistence format directly.

## Current Contract Notes

1. Stream contract is request-id aware and supports explicit terminal event semantics.
2. Startup readiness allows optional-model warnings without blocking required-model paths.
3. Provisioning/runtime boundaries are explicit: activation and startup checks are separate steps.
4. Runtime performance config (profile + GPU capability) is applied through runtime contracts, not UI-only flags.
