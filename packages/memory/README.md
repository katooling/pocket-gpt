# memory

Local memory and retrieval contracts.

## Initial Responsibilities

- persist summary chunks
- retrieve relevant context snippets
- memory pruning and retention enforcement

## Storage Direction

- SQLite as canonical data store
- retrieval index behind an abstraction interface

## Implemented MVP Scaffolding

- `InMemoryMemoryModule` for stage execution:
  - chunk save
  - overlap-based retrieval
  - bounded pruning
