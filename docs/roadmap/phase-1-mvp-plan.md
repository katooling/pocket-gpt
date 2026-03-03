# Phase 1 MVP Plan

## Objective

Ship a usable, privacy-first offline assistant MVP with text + single-image support and safe local tools.

Execution tracker:

- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/roadmap/next-steps-execution-plan.md`
- `docs/roadmap/product-roadmap.md`
- `docs/roadmap/team-workstreams.md`
- `docs/testing/test-strategy.md`

## Priority Backlog (Ordered)

1. Runtime integration baseline (`llama.cpp`) for iOS and Android shells
2. Streaming chat UI and conversation/session state
3. Model manager (download, verify, version, eviction)
4. Routing policy v1 (device class + battery + thermal)
5. Tool runtime v1 (allowlisted schema-validated local tools)
6. Memory v1 (rolling summary + retrieval)
7. Image input path v1 (single image/document photo)
8. Privacy settings panel and retention controls
9. Local diagnostics exporter for benchmark/bug triage
10. Beta hardening (OOM/thermal guards, crash recovery)

## Dependencies

1. Phase 0 ADRs accepted
2. Feasibility thresholds met on mid-tier devices
3. Model artifact pipeline and integrity checks available

## Milestones

### M1 (Weeks 1-2)

- baseline runtime in both apps
- streaming text chat demo

### M2 (Weeks 3-4)

- routing policy and model manager
- tool runtime v1

### M3 (Weeks 5-6)

- memory v1
- image path v1

### M4 (Weeks 7-8)

- performance hardening
- privacy UX finalization
- beta candidate

## Go/No-Go Recommendation Template

Go if all are true:

1. Mid-tier benchmark thresholds pass
2. No blocker risks in security/privacy path
3. Crash-free stability target is met in beta runs

No-Go if any are true:

1. Sustained thermal regressions remain unresolved
2. Frequent OOM on target mid-tier devices
3. Privacy controls not enforceable in implementation

## MVP Completion Definition

MVP is considered complete when all are true:

1. Stage 1-6 exit criteria in `docs/roadmap/next-steps-execution-plan.md` are satisfied.
2. Required evidence artifacts are present for benchmark and soak runs.
3. Cross-functional go/no-go packet is approved.
