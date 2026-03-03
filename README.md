# PocketAgent

Foundation repository for an offline, privacy-first mobile AI product.

## Layout

- `docs/`: product, architecture, feasibility, security, and roadmap docs
- `apps/mobile-ios/`: iOS application shell
- `apps/mobile-android/`: Android application shell
- `packages/core-domain/`: shared product/domain logic contracts
- `packages/inference-adapters/`: runtime adapter interfaces and policy routing
- `packages/tool-runtime/`: local tool execution and schema validation contracts
- `packages/memory/`: memory and retrieval contracts
- `scripts/benchmarks/`: benchmark harness docs and scripts

## Current Status

- Phase 0 docs and architecture are in place.
- Android-first MVP stage scaffolding (Stages 1-6) is implemented in code/docs.
- Next step is physical Android device execution using the test playbook.
