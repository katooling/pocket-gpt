# core-domain

Shared domain contracts for conversation/session behavior.

## Initial Contracts

- session lifecycle API
- prompt context assembly API
- turn/event model

## Rules

- no runtime-specific code in this package
- no direct persistence implementation in this package

## Implemented MVP Scaffolding

- `InMemoryConversationModule`
- `DefaultPolicyModule`
- `InMemoryObservabilityModule`
