# Modular Monolith Architecture

## Why Modular Monolith

Phase 0 and MVP prioritize speed, correctness, and low operational complexity. A modular monolith in a monorepo enables shared tooling and strict boundaries without distributed systems overhead.

## Module Contracts

### `ConversationModule`

Responsibilities:

- session lifecycle
- turn state
- prompt assembly

Public interface:

- `createSession()`
- `appendUserTurn()`
- `appendAssistantTurn()`
- `buildPromptContext()`

Owner: Product/Core

### `InferenceModule`

Responsibilities:

- model load/unload orchestration
- streaming token generation
- adapter abstraction over runtimes

Public interface:

- `listAvailableModels()`
- `loadModel()`
- `generateStream()`
- `unloadModel()`

Owner: AI Runtime

### `RoutingModule`

Responsibilities:

- capability policy selection by device class, battery, thermal state, and task type

Public interface:

- `selectModel(task, deviceState)`
- `selectContextBudget(task, deviceState)`

Owner: AI Runtime

### `ToolModule`

Responsibilities:

- schema-validated tool call parsing
- deterministic local tool execution

Public interface:

- `validateToolCall()`
- `executeToolCall()`
- `listEnabledTools()`

Owner: Platform

### `MemoryModule`

Responsibilities:

- rolling summaries
- retrieval of relevant memory snippets

Public interface:

- `saveMemoryChunk()`
- `retrieveRelevantMemory()`
- `pruneMemory()`

Owner: Core/AI

### `PolicyModule`

Responsibilities:

- local-only policy enforcement
- retention and permission checks

Public interface:

- `isNetworkAllowedForAction()`
- `getRetentionPolicy()`
- `enforceDataBoundary()`

Owner: Security/Platform

### `ObservabilityModule`

Responsibilities:

- privacy-safe local diagnostics
- benchmark/perf telemetry export

Public interface:

- `recordLatencyMetric()`
- `recordThermalSnapshot()`
- `exportLocalDiagnostics()`

Owner: Platform

## Dependency Rules

1. `ConversationModule` may depend on `MemoryModule` and `RoutingModule`, never on runtime internals.
2. `InferenceModule` is accessed through adapter interfaces only.
3. `ToolModule` must not call platform APIs directly outside approved wrappers.
4. `PolicyModule` is authoritative for network and data movement rules.
5. No module accesses another module's private data store directly.

## Suggested Package Mapping

- `packages/core-domain`: `ConversationModule`, shared contracts
- `packages/inference-adapters`: `InferenceModule`, `RoutingModule` policies
- `packages/tool-runtime`: `ToolModule`
- `packages/memory`: `MemoryModule`
- app layers call modules through public interfaces only
