# Tech Stack Decision

## Decision Summary

1. Baseline inference for feasibility and MVP: `llama.cpp` + GGUF quantized models
2. iOS optimization track (post-baseline): Core ML + Metal
3. Android optimization track (post-baseline): LiteRT/NNAPI evaluation
4. Shared app-domain architecture: Kotlin Multiplatform + native UI (`SwiftUI`, `Jetpack Compose`)
5. Local storage baseline: SQLite (+ lightweight vector retrieval abstraction)
6. Tool execution: strict JSON schema validation + local sandbox only

## Why This Stack

### Baseline Reliability

`llama.cpp` is the fastest cross-platform path to proven local inference and allows early feasibility measurements before deeper platform-specific optimization.

### Product Fit

Local-first privacy claims require deterministic local execution and local storage defaults.

### Risk Control

Platform-specific acceleration is deferred to optimization tracks so MVP does not block on conversion/kernel instability.

## Alternatives Considered

### Core ML-first from day one

Pros:

- high iOS performance and energy efficiency

Cons:

- conversion and operator compatibility risk
- no Android path reuse

Decision: not day-1 baseline; keep as optimization track.

### LiteRT-first from day one

Pros:

- Android-optimized path potential

Cons:

- conversion complexity and OEM fragmentation risk

Decision: evaluate after baseline parity exists.

### Flutter-first UI

Pros:

- single UI codebase

Cons:

- performance and native integration tradeoffs for runtime-heavy app

Decision: prefer KMP shared domain with native UI.

## Non-Negotiables

1. Offline-first behavior must work without cloud dependency.
2. No arbitrary code execution from model output.
3. Any network call must be explicit and policy-gated.
4. Benchmarks drive routing defaults, not assumptions.

## Implementation Maturity (As Of 2026-03-03)

| Area | Decision | Current Maturity |
|---|---|---|
| Baseline inference | `llama.cpp` + GGUF | Planned; scaffold currently uses smoke adapter |
| iOS optimization | Core ML + Metal | Planned; no active implementation yet |
| Android optimization | LiteRT/NNAPI | Planned; no active implementation yet |
| Shared architecture | KMP shared domain + native UI | Partial; Kotlin/JVM modular scaffolding in place |
| Local storage | SQLite baseline | Planned; in-memory implementation used for scaffolding |
| Tool execution | strict schema validation + local sandbox | Partial; allowlist and lightweight validation implemented |
| Voice (STT/TTS) | offline-preferred, policy-gated | Planned; post-MVP design and benchmark phase |
