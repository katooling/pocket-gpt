# UX-13 Stuck Send + Timeout Recovery UX

Last updated: 2026-03-05
Owner: Product + Design + Android
Support: QA, Engineering
Status: Ready

## Objective

Define deterministic user-facing recovery when message send appears stalled (`Loading` or placeholder persists) and generation exceeds timeout SLA.

## User Story

As a user, when a send appears stuck, I can understand what happened and recover without losing session context.

## Scope

1. Runtime timeout/cancel copy and CTA hierarchy in chat surface.
2. Consistent mapping of timeout failures to `UI-RUNTIME-001` with actionable guidance.
3. Recovery flow after timeout:
   - retry send,
   - refresh runtime checks,
   - open model setup.
4. Telemetry/evidence linkage to journey send-capture stage.

## Acceptance

1. Timeout failure shows deterministic copy with explicit next action.
2. Composer exits sending state after timeout and user can retry immediately.
3. Session timeline preserves prompt context across timeout recovery.
4. Instrumented + Maestro assertions cover timeout/recovery UX.
5. Journey send-capture reports `phase=completed` and `placeholder_visible=false` in passing runs.

## Evidence Targets

1. `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/ChatViewModelTest.kt`
2. `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/MainActivityUiSmokeTest.kt`
3. `tests/maestro/scenario-a.yaml`
4. `scripts/benchmarks/runs/YYYY-MM-DD/<device>/journey/<stamp>/journey-report.json`
