# ENG-22 Provisioning Startup-Check Lane Blocker

Last updated: 2026-03-08
Owner: Engineering
Support: QA, Product
Status: In Progress

## Objective

Unblock required device lanes (`android-instrumented`, `maestro`, and strict `journey`) by resolving provisioning preflight failure in `RealRuntimeProvisioningInstrumentationTest`.

## Failure Signature

`com.pocketagent.android.RealRuntimeProvisioningInstrumentationTest#seedModelsAndVerifyStartupChecks`

Failure text:

`Startup checks failed after provisioning: Optional runtime model unavailable: smollm2-135m-instruct-q4_k_m. MODEL_ARTIFACT_CONFIG_MISSING:model=smollm2-135m-instruct-q4_k_m;field=payload_or_path,sha256,provenance_signature`

## Scope

1. Determine whether optional model checks should be:
   - downgraded to non-blocking warnings in provisioning instrumentation lane, or
   - fully satisfied by runtime config/provisioning metadata at preflight time.
2. Patch the failing contract in the owning layer (instrumentation preflight/test expectation, runtime startup-check contract, or provisioning metadata wiring).
3. Add regression coverage for the selected behavior so lane-level preflight cannot silently re-break.
4. Re-run:
   - `python3 tools/devctl/main.py lane android-instrumented`
   - `python3 tools/devctl/main.py lane maestro`
   - `python3 tools/devctl/main.py lane journey --mode strict --repeats 3 --reply-timeout-seconds 90`

## Acceptance

1. Provisioning preflight instrumentation test passes on required-tier device.
2. Strict journey rerun executes all requested repeats (no preflight short-circuit).
3. Latest WP-13 evidence note records pass/fail outcomes and artifact roots for all three required lanes.

## Current Evidence

1. `docs/operations/evidence/wp-13/2026-03-08-qa-lane-rerun-post-p0-parser.md`
2. `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/android-instrumented/20260308-132026/`
3. `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/maestro/20260308-132156/`
4. `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/journey/20260308-132325/`
