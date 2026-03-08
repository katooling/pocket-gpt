# WP-13 Usability Gate Run 02 (Blocked Checkpoint)

Last updated: 2026-03-08  
Owner: Product  
Support: QA, Design, Engineering

## Status

`blocked` - moderated run-02 execution is paused until required lane reliability rerun clears provisioning preflight.

## Blocker

1. `ENG-22` provisioning startup-check lane blocker:
   - `docs/operations/tickets/eng-22-provisioning-startup-check-lane-blocker.md`
2. Supporting evidence:
   - `docs/operations/evidence/wp-13/2026-03-08-qa-lane-rerun-post-p0-parser.md`

## Impact

1. Required metrics for Workflow A/B/C completion, onboarding completion, runtime/model confusion, and privacy confusion remain uncollected for run-02.
2. `PROD-10` launch gate rows that depend on `QA-WP13-RUN02` remain in FAIL/HOLD state until blocker closure and moderated execution.

## Raw Artifact Roots

1. `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/android-instrumented/20260308-132026/`
2. `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/maestro/20260308-132156/`
3. `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/journey/20260308-132325/`

## Exit Criteria

1. `android-instrumented`, `maestro`, and strict `journey` reruns pass after ENG-22 closure.
2. Moderated run-02 packet is executed with measured values (no placeholders).
