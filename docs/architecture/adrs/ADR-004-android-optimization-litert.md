# ADR-004: Android Optimization Track via LiteRT/NNAPI

## Status

Accepted

## Context

Android hardware is fragmented. Vendor acceleration paths can improve performance but have varying support by chip and OEM.

## Decision

Evaluate LiteRT/NNAPI as an optimization track after baseline `llama.cpp` integration and parity are established.

## Consequences

Positive:

1. Potential NPU/GPU acceleration on supported devices
2. Better scalability across high-end Android targets

Negative:

1. Driver and backend variability
2. Conversion and debugging complexity

Mitigation:

1. Maintain robust fallback path to baseline runtime
2. Qualify supported device list and feature flags
