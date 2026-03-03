# ADR-001: Monorepo with Modular Monolith

## Status

Accepted

## Context

The project starts with a small team, tight timeline, and heavy cross-cutting requirements (mobile runtimes, privacy policy, model routing, tools, memory). Early microservices would add coordination and deployment overhead without clear benefit.

## Decision

Use a monorepo and implement application logic as a modular monolith with strict package boundaries and public interfaces.

## Consequences

Positive:

1. Faster iteration and simpler CI setup
2. Shared contracts and reduced duplication
3. Easier architectural consistency across mobile platforms

Negative:

1. Requires discipline to preserve boundaries
2. Potential for coupling if interfaces are bypassed

Mitigation:

1. Enforce package-level API boundaries
2. Add architecture checks and ownership docs
