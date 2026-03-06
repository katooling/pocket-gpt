# UX-12 Recovery Journey Spec

Last updated: 2026-03-05
Owner: Product + Design + Android
Status: Ready

## Objective

Define a decision-complete first-run recovery journey for users who land in `Not ready` runtime state.

## Canonical User Stories

1. As a user, when runtime is not ready, I can understand why and what to do next.
2. As a user, I can recover by importing or downloading required models and activating them.
3. As a user, I can leave and return without losing download/provisioning progress.
4. As a user, after recovery I can verify runtime is `Ready` before sending.
5. As a user, I get deterministic guidance for checksum/provenance/compatibility failures.

## Journey Contract

1. `NotReady` -> open model setup.
2. Choose path:
   - import local files, or
   - download (built-in manager in the standard app build).
3. Verify result:
   - `verified and active`, or
   - `verified, activation pending`.
4. If pending: activate version.
5. Refresh runtime checks.
6. Confirm `Runtime: Ready` + expected backend.
7. Composer/image actions unlock.

## UX Event Schema Contract

These events must be captured in pilot evidence notes:

1. `onboarding_completed`
2. `runtime_not_ready_visible`
3. `model_setup_opened`
4. `model_import_started`
5. `model_download_started`
6. `model_version_activated`
7. `runtime_ready`
8. `first_useful_answer_ms`

Required event fields:

1. `event_name`
2. `event_time_utc`
3. `build_id`
4. `device_model`
5. `session_id`
6. `runtime_backend`
7. `runtime_status`
8. `error_code` (optional)
9. `duration_ms` (for timing events)

## Success Targets (Pilot)

1. P50 model setup completion time <= 6 minutes.
2. Recovery completion (NotReady -> Ready) >= 85%.
3. Runtime/model confusion <= 10% in moderated cohort.
4. No open `UX-S0`/`UX-S1` recovery blockers.

## Test and Evidence Mapping

1. Instrumentation: `MainActivityUiSmokeTest`, `RealRuntimeProvisioningInstrumentationTest`.
2. Maestro: scenario B and scenario C with recovery assertions.
3. Journey lane: `journey-report.json` + `journey-summary.md`.
4. Packet: `docs/operations/wp-13-usability-gate-packet-template.md`.
