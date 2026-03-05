# Implemented UX Behavior Reference

Last updated: 2026-03-05  
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
3. Download path is flavor-gated (`internalDownload` only) and supports queue/pause/resume/retry.
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
   - terminal guidance for checksum/provenance/runtime compatibility/storage/network failure reasons
