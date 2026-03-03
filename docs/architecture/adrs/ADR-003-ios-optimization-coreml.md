# ADR-003: iOS Optimization Strategy via Core ML/Metal

## Status

Accepted

## Context

The baseline runtime establishes feasibility, but iOS energy efficiency and sustained performance can improve with native acceleration.

## Decision

Pursue Core ML + Metal as a post-baseline optimization track for iOS once functional parity is achieved.

## Consequences

Positive:

1. Better potential performance/watt on supported devices
2. Improved long-session thermal behavior

Negative:

1. Conversion/operator compatibility complexity
2. Additional maintenance burden for dual runtime support

Mitigation:

1. Keep runtime abstraction stable
2. Gate migration by measured benchmark improvements
