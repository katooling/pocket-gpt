# Implemented UX Behavior Reference

Last updated: 2026-03-06  
Owner: Product + Android

## Purpose

Capture implemented user-facing behavior that is easy to miss when reading only ticket summaries.

## Tool Prompt UX

1. `Tools` entry point presents natural-language prompt shortcuts (not direct hardcoded tool execution).
2. User can edit any suggested prompt before sending.
3. Tool schema/runtime failures map to deterministic user-facing codes (`UI-TOOL-SCHEMA-001`, `UI-RUNTIME-001`).

## Privacy Sheet Behavior

1. Top-bar privacy icon opens an in-app privacy explainer sheet.
2. Sheet copy explicitly states local storage and offline-policy behavior.
3. Diagnostics redaction is explained in user-visible copy and backed by runtime tests.

## Empty-State Prompt Cards

1. Empty chat timeline shows suggested starter prompts for:
   - quick answer
   - image help
   - local search
   - reminder creation
2. Tapping a card pre-fills the composer; user still confirms by tapping `Send`.

## Runtime Status and Backend Semantics

1. Runtime status values:
   - `Not ready`: model artifacts missing/invalid or startup checks failing
   - `Loading`: active runtime work in progress
   - `Ready`: runtime path healthy for current request flow
   - `Error`: runtime/startup failure that requires retry or recovery action
2. Backend identity is shown as `Backend: <value>` (`NATIVE_JNI`, `ADB_FALLBACK`, `UNAVAILABLE`) for support triage.
3. Runtime error banner includes deterministic code + user message.
4. Error banner CTA hierarchy is fixed:
   - primary: `Fix model setup`
   - secondary: `Refresh runtime checks`
   - tertiary: `Show technical details`

## Runtime Performance Profiles and GPU Toggle

1. Advanced controls expose exactly three runtime performance profiles:
   - `BATTERY`: longest timeout window, reduced decode pressure, conservative thread/batch defaults
   - `BALANCED`: default profile for normal usage
   - `FAST`: higher throughput preference with larger decode budget
2. Profile selection persists with session state restore and is reapplied on send actions.
3. GPU acceleration toggle is visible in advanced controls:
   - enabled only when runtime/backend reports support
   - disabled state renders explicit unavailable copy
4. Profile + GPU settings are applied through the app runtime contract and not by direct UI-only overrides.

## Simple-First First Session Contract

1. First-session state machine is explicit:
   - `Onboarding`
   - `GetReady`
   - `ReadyToChat`
   - `FirstAnswerDone`
   - `FollowUpDone`
   - `AdvancedUnlocked`
2. `Get ready` is the primary blocked-state CTA and defaults to `0.8B` download path.
3. `Tools` and `Advanced` entry points remain hidden until the follow-up response is complete.
4. After unlock, app shows an explicit transition cue and reveals advanced/tool entry points.
5. Stage + unlock flags are persisted so app restart does not regress first-session progress.

## Runtime Telemetry Labels in UI

1. Advanced controls runtime details include:
   - active model id
   - last first-token latency
   - last total latency
   - last prefill latency
   - last decode latency
   - last decode rate (tokens/sec)
2. These labels are support-facing transparency signals and should be captured in QA evidence when triaging performance regressions.

## Model Residency Defaults

1. Runtime keeps model loaded while app is foreground by default.
2. Idle unload TTL defaults to 10 minutes.
3. Warmup-on-startup defaults to enabled unless overridden by runtime/test lane controls.

## Send Timeout and Cancel Semantics

1. Send operations enforce runtime timeout guards; timeout maps to deterministic runtime error UX (`UI-RUNTIME-001`).
2. On timeout, chat send state is released so user can retry without restarting the app.
3. Runtime cancellation is attempted for active session generation on timeout/cancel pathways.
4. JNI runtime path supports active cancel; fallback runtime path is non-interruptible and surfaces deterministic timeout guidance.

## Diagnostics UX

1. `Advanced` sheet includes `Export diagnostics`.
2. Diagnostics output is rendered in timeline as a system message.
3. Support/QA workflows should capture diagnostics output alongside runtime backend + status.

## Model Provisioning and Download Manager UX (Phase-2)

1. Model setup sheet includes explicit sections for:
   - required models
   - downloads
   - installed versions
   - storage summary
2. Import path remains available in all builds and writes versioned model records.
3. Download path is available in the primary app build and supports queue/pause/resume/retry.
4. Download completion result is `verified, activation pending` (no auto-activation).
5. Installed versions can be manually activated; active version deletion is blocked.
6. Runtime unlock is only confirmed after activation + refresh startup checks.

## State Transition Feedback

1. Import success feedback:
   - `verified and active`
   - `verified, activation pending`
2. Refresh feedback:
   - explicit message after runtime checks refresh
3. Download feedback:
   - terminal guidance for checksum/runtime compatibility/storage/network failure reasons
   - provenance metadata is currently informational in app download flow (`INTEGRITY_ONLY`) and not a hard-block failure

## Manifest Outage Behavior

1. If manifest fetch fails/returns no usable entries, setup UX keeps import path visible as primary recovery.
2. Download state is treated as degraded; runtime remains usable if required active models are already verified.
3. Recovery copy must include issue state plus next action (`import`, `retry manifest`, or `refresh runtime checks`).

## Journey Send-Capture Evidence Semantics

1. Journey lane includes deterministic send-capture fields:
   - `phase`
   - `elapsed_ms`
   - `runtime_status`
   - `backend`
   - `active_model_id`
   - `placeholder_visible`
2. Passing send-capture requires `phase=completed` and `placeholder_visible=false` at SLA checkpoint.
