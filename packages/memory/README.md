# memory

Local memory and retrieval contracts for chat continuity.

## Responsibilities

- persist memory chunks
- retrieve relevant snippets for follow-up context
- prune history under bounded retention

## Implementations

- `InMemoryMemoryModule` (tests/fast paths)
- `SqliteMemoryModule` (persisted storage)
- file-backed runtime module wiring used by app runtime composition

## Contract Notes

1. Retrieval behavior is deterministic and bounded.
2. Runtime orchestration consumes this via `MemoryModule` interface.
