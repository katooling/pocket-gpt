# core-domain

Shared product/domain contracts for sessions, routing mode, policy, and observability.

## Key Contracts

- `ConversationModule`
- `PolicyModule`
- `ObservabilityModule`
- session/turn primitives (`SessionId`, `Turn`)
- routing selectors (`RoutingMode`)

## Current Implementations

- `InMemoryConversationModule`
- `DefaultPolicyModule`
- `InMemoryObservabilityModule`

## Boundary Rules

1. No runtime-backend implementation details in this package.
2. No Android/UI dependencies.
3. No direct cross-package persistence format coupling.
