# WP-13 QA Evidence: Lane Rerun After P0 UID Parser Hardening

Date: 2026-03-08  
Owner: QA + Engineering  
Device: `DEVICE_SERIAL_REDACTED` (`SM-A515F`, Android 13)

## Objective

Re-run required device lanes after `devctl` package UID parsing was hardened to accept `userId`, `appId`, and `uid` metadata in package dumpsys output.

## Commands and Outcomes

1. `python3 -m unittest tools/devctl/tests/test_lanes.py`
   - Result: PASS (`Ran 50 tests`) with parser coverage for `userId`, `appId`, and `uid` plus preflight-path tests.
2. `ADB_SERIAL='DEVICE_SERIAL_REDACTED' python3 tools/devctl/main.py lane android-instrumented`
   - Result: FAIL during provisioning instrumentation preflight.
3. `ADB_SERIAL='DEVICE_SERIAL_REDACTED' python3 tools/devctl/main.py lane maestro`
   - Result: FAIL during provisioning instrumentation preflight.
4. `ADB_SERIAL='DEVICE_SERIAL_REDACTED' python3 tools/devctl/main.py lane journey --mode strict --repeats 3 --reply-timeout-seconds 90`
   - Result: FAIL during provisioning instrumentation preflight before strict journey repeats started.

## Failure Signature (All Three Lane Runs)

`com.pocketagent.android.RealRuntimeProvisioningInstrumentationTest#seedModelsAndVerifyStartupChecks` failed with:

`Startup checks failed after provisioning: Optional runtime model unavailable: smollm2-135m-instruct-q4_k_m. MODEL_ARTIFACT_CONFIG_MISSING:model=smollm2-135m-instruct-q4_k_m;field=payload_or_path,sha256,provenance_signature`

## Artifact Paths

1. Prior parser-verified preflight snapshot:
   - `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/android-instrumented/20260308-130814/real-runtime-preflight.json`
2. Android instrumented rerun root:
   - `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/android-instrumented/20260308-132026/`
3. Maestro rerun root:
   - `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/maestro/20260308-132156/`
4. Journey strict rerun root:
   - `scripts/benchmarks/runs/2026-03-08/DEVICE_SERIAL_REDACTED/journey/20260308-132325/`

## Decision Note

1. P0 parser brittleness is closed (lane no longer fails on package UID extraction).
2. Required lane reliability remains blocked by provisioning startup-check failure and strict journey evidence cannot be promoted from this run.
3. Follow-up tracked in ticket: `docs/operations/tickets/eng-22-provisioning-startup-check-lane-blocker.md`.
