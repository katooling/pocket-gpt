# Wave-3 Parallel Execution Plan

Status: in progress on 2026-03-27.

## Objective

Land the next optimization-control-plane improvements without mixing unrelated concerns:

- make promotion evidence requirements trigger automatically for optimization-sensitive changes
- stop runtime tuning recommendations from leaking across materially different runtime envelopes
- formalize backend qualification/capability state in the Android qualification slice

## Team Structure

### Senior Lead

Owner: main agent

Responsibilities:

- choose bounded write scopes
- define evidence-based Definition of Done
- keep changes non-overlapping
- integrate and verify wave closure

### Lead A: Promotion Gate

Owner scope:

- `tools/devctl/gates.py`
- `tools/devctl/tests/test_gates.py`
- optional execution note for the slice

Task:

- run `stage2 --profile quick` from the promotion gate when optimization-sensitive changes are present

Definition of Done:

1. Promotion gate runs stage2 quick on evidence-sensitive changes.
2. Low-risk changes skip stage2 quick with an explicit recorded reason.
3. Gate reports show whether stage2 quick was required and why.
4. Tests cover trigger and skip paths.

Evidence:

- changed-file policy
- gate report metadata
- unit tests for trigger/skip behavior

### Lead B: Runtime Tuning Identity

Owner scope:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeTuningStore.kt`
- Android runtime tuning tests in the same package
- optional execution note for the slice

Task:

- expand tuning recommendation/history identity beyond device/profile/mode/model ID

Definition of Done:

1. Keys and stored payload differentiate at least model version, quant/artifact signal, and context bucket when that evidence exists.
2. Tests prove materially different envelopes do not collide.
3. Legacy/missing metadata still reads safely.
4. Any unsatisfied backend-identity axis is documented with the exact blocking seam.

Evidence:

- pref/history key changes
- persisted payload shape
- collision tests

### Lead C: Backend Qualification States

Owner scope:

- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/GpuOffloadQualification.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/RuntimeDiagnosticsSnapshot.kt`
- Android runtime qualification/diagnostics tests in the same package
- optional execution note for the slice

Task:

- separate coarse qualification from capability/feature qualification in the Android GPU qualification slice

Definition of Done:

1. The slice exposes more than a single coarse success/failure concept.
2. Diagnostics and/or parsed state include canonical capability fields for compiled backend(s), discovered or active backend, and feature qualification where evidence exists.
3. Tests cover the richer state and one unknown/fallback path.
4. Any remaining cross-module seam is documented precisely.

Evidence:

- richer diagnostics/state model
- parser/qualification tests

## Integration Rules

1. No team may edit another team’s write scope.
2. If a required signal is unavailable at the current seam, document the seam instead of inventing fake state.
3. Every landed behavior change must be covered by tests.
4. Senior lead closes the wave only after full affected-suite verification.
