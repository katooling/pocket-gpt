# Documentation Sync Audit (2026-03-08)

Owner: Product + Engineering
Execution state: completed

## Scope

Audit project documentation against current code and runtime behavior, then reduce drift/duplication.

## What Was There (Recency Snapshot)

## Recently maintained (2026-03-06 to 2026-03-08)

- `scripts/dev/README.md`
- `docs/testing/test-strategy.md`
- `docs/testing/android-dx-and-test-playbook.md`
- `docs/ux/implemented-behavior-reference.md`
- `docs/roadmap/current-release-plan.md`
- `docs/product/feature-catalog.md`

## Older/stale relative to code changes (mostly 2026-03-03 to 2026-03-05)

- `docs/architecture/system-context.md`
- `docs/architecture/modular-monolith.md`
- `docs/architecture/tech-stack-decision.md`
- `docs/ux/onboarding-spec.md`
- `docs/testing/wp-09-ui-regression-matrix.md`
- package READMEs under `packages/*` (partial)
- `scripts/benchmarks/README.md`

## Missing or Under-documented vs Codebase

1. No dedicated module docs for:
   - `packages/app-runtime`
   - `packages/native-bridge`
2. Onboarding doc still described hidden advanced/tools behavior that no longer matches app behavior.
3. Architecture stack docs still reflected outdated Android optimization framing.
4. Weekly QA matrix did not fully reflect current `UI-11..UI-15` acceptance scope.

## Excessive / Duplicate Docs Identified

1. Duplicate command guidance between testing companion docs and canonical command docs.
2. Secondary READMEs (`scripts/android`, `tests/maestro`, `scripts/benchmarks`) repeated runnable command details already owned by `scripts/dev/README.md`.

## Execution Log

## Updated (sync to current code behavior)

1. `docs/architecture/system-context.md`
2. `docs/architecture/modular-monolith.md`
3. `docs/architecture/tech-stack-decision.md`
4. `docs/ux/onboarding-spec.md`
5. `docs/ux/model-management-flow.md`
6. `docs/testing/wp-09-ui-regression-matrix.md`
7. `packages/core-domain/README.md`
8. `packages/inference-adapters/README.md`
9. `packages/tool-runtime/README.md`
10. `packages/memory/README.md`
11. `scripts/benchmarks/README.md`

## Added (missing docs)

1. `packages/app-runtime/README.md`
2. `packages/native-bridge/README.md`

## Replaced/Consolidated (reduced duplication)

1. `scripts/android/README.md` -> internal-helper contract with canonical pointers.
2. `tests/maestro/README.md` -> flow contract index with canonical pointers.
3. `docs/testing/README.md` -> consolidated testing doc index.

## Deleted (duplicate/excessive)

1. `docs/testing/just-cli-android-validation-plan.md`
2. `docs/testing/stage-2-benchmark-runbook.md`

## Remaining Follow-ups

1. Operations `draft/template` docs under `docs/operations/` are intentionally retained for active cross-functional work; consider a future archival pass after WP-13 closure.
2. Keep `docs/ux/implemented-behavior-reference.md` as the primary behavior truth when UI/runtime changes land.
