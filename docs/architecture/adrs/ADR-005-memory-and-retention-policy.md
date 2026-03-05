# ADR-005: Memory Architecture and Retention Policy

## Status

Accepted

## Context

The product requires local memory and retrieval while preserving privacy guarantees and predictable resource usage on mobile devices.

## Decision

Use shared file-backed local persistence as the canonical backend with a lightweight retrieval index abstraction. Enforce explicit retention policy defaults and local-only data boundaries.

## Consequences

Positive:

1. Reliable local storage shared across runtime surfaces
2. Clear policy enforcement for privacy claims
3. Bounded resource behavior via pruning controls

Negative:

1. Retrieval quality tuning required for memory relevance
2. Potential migration complexity as features grow

Mitigation:

1. Define memory schema versioning early
2. Add periodic pruning and compaction routines
3. Keep retrieval interface backend-agnostic
