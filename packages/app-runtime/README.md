# app-runtime

Runtime orchestration layer composed from domain, inference, tool, memory, and bridge packages.

## Responsibilities

- startup checks orchestration
- send/stream orchestration and timeout/cancel handling
- model lifecycle and template availability enforcement
- diagnostics export path
- runtime composition root and facade wiring

## Key Components

- `RuntimeOrchestrator`
- `RuntimeCompositionRoot`
- `MvpRuntimeFacade`
- `SendMessageUseCase`
- `StartupChecksUseCase`
- `ToolExecutionUseCase`
- `ImageAnalyzeUseCase`
- `RuntimeConfig`

## Boundary Rules

1. No Android UI dependencies.
2. Consumes bridge/runtime through package interfaces.
3. Exposes stable orchestration contracts to app shells.
