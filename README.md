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
- Android-first MVP stages 1-6 are scaffolded across modules.
- Real runtime/device validation is still pending:
  - real `llama.cpp` Android integration
  - physical-device benchmark/soak evidence
  - production-ready memory/tool hardening paths
- Immediate execution guide:
  - `docs/operations/execution-board.md`
  - `docs/operations/role-playbooks/`
  - `docs/roadmap/next-steps-execution-plan.md`
  - `docs/roadmap/product-roadmap.md`
  - `docs/product/feature-catalog.md`
  - `docs/testing/test-strategy.md`
  - `docs/testing/android-dx-and-test-playbook.md`

## Local Verification Command

Run the engineering baseline build/test command:

```bash
bash scripts/dev/verify.sh
```
