# WP-13 Engineering Evidence: Runtime Performance Profile + UX Hardening

Date: 2026-03-06
Owner: Engineering
Commit: `f82bf16`

## Scope

Backfill evidence for runtime and UX work that was merged but missing from WP-13 evidence index.

## Implemented Changes

1. Added runtime performance profile contract (`BATTERY`, `BALANCED`, `FAST`) and adaptive preset mapping.
2. Added advanced controls UX wiring for performance profile selection and GPU acceleration toggle state.
3. Added runtime telemetry readouts in UI state for prefill/decode/decode-rate visibility.
4. Strengthened streaming/runtime UX reliability and provisioning state coverage.
5. Added/updated tests for profile selection and provisioning snapshot behavior.

## Files of Record

- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/PerformanceProfiles.kt`
- `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeOrchestrator.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/SettingsSheet.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatViewModel.kt`
- `apps/mobile-android/src/test/kotlin/com/pocketagent/android/runtime/RuntimeProvisioningSnapshotTest.kt`
- `apps/mobile-android/src/test/kotlin/com/pocketagent/android/ui/ChatViewModelTest.kt`

## Doc Sync Follow-up

This evidence note closes the documentation gap for commit `f82bf16` and is paired with updates to:

- `docs/prd/phase-0-prd.md`
- `docs/ux/implemented-behavior-reference.md`
- `docs/ux/model-management-flow.md`
- `docs/product/feature-catalog.md`
