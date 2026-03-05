# WP-13 QA Evidence: Wireless Device Lane Rerun (Android Instrumented + Maestro + Journey)

Date: 2026-03-05  
Owner: Engineering + QA  
Device: `adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp` (`SM-A515F`, Android 13)

## Scope

Re-run previously blocked device lanes after wireless-debug attachment:

1. `python3 tools/devctl/main.py lane android-instrumented`
2. `python3 tools/devctl/main.py lane maestro`
3. `python3 tools/devctl/main.py lane journey --repeats 1`

## Commands and Outcomes

1. `ADB_SERIAL='adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp' python3 tools/devctl/main.py lane android-instrumented`
   - Result: PASS (`BUILD SUCCESSFUL`; connected instrumentation completed, `0 failed`, RC-only classes skipped by explicit gating flags).
2. `ADB_SERIAL='adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp' python3 tools/devctl/main.py lane maestro`
   - Result: PASS (Scenario A/B/C flows all completed; no runtime error banner assertions failed).
3. `ADB_SERIAL='adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp' python3 tools/devctl/main.py lane journey --repeats 1`
   - Result: PASS (instrumentation journey + Maestro Scenario A/B/C all passed; report and summary generated).

## Artifact Paths

1. Android instrumented lane:
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/android-instrumented/20260305-152034/real-runtime-preflight.json`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/android-instrumented/20260305-152034/logcat.txt`
2. Maestro lane:
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-153028/logcat.txt`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-153028/real-runtime-preflight.json`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-153028/maestro-debug/scenario-a/maestro-output.txt`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-153028/maestro-debug/scenario-b/maestro-output.txt`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-153028/maestro-debug/scenario-c/maestro-output.txt`
3. Journey lane:
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/journey/20260305-153850/journey-report.json`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/journey/20260305-153850/journey-summary.md`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/journey/20260305-153850/run-01/maestro-debug/scenario-a/maestro-output.txt`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/journey/20260305-153850/run-01/maestro-debug/scenario-b/maestro-output.txt`
   - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/journey/20260305-153850/run-01/maestro-debug/scenario-c/maestro-output.txt`

## Notes

1. Wireless runs were coordinated using per-device lock files under:
   - `scripts/benchmarks/device-env/locks/`
2. Lane robustness updates validated during this rerun:
   - remote media path preflight no longer depends on fragile `sh -lc` quoting
   - `lane maestro` now ensures test APK install before provisioning instrumentation probe

## Addendum (P1.5 De-gating Rerun Refresh)

Date: 2026-03-05 (later rerun window)  
Scope: Revalidation after single-build download-manager de-gating and Maestro recovery flow hardening.

1. `ADB_SERIAL='adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp' python3 tools/devctl/main.py lane android-instrumented`
   - Result: PASS
   - Artifacts:
     - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/android-instrumented/20260305-211837/real-runtime-preflight.json`
     - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/android-instrumented/20260305-211837/logcat.txt`
2. `ADB_SERIAL='adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp' python3 tools/devctl/main.py lane maestro`
   - Result: PASS
   - Artifacts:
     - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-214636/real-runtime-preflight.json`
     - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-214636/logcat.txt`
     - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-214636/maestro-debug/scenario-a/maestro-output.txt`
     - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-214636/maestro-debug/scenario-b/maestro-output.txt`
     - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-214636/maestro-debug/scenario-c/maestro-output.txt`
     - `scripts/benchmarks/runs/2026-03-05/adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp/maestro/20260305-214636/maestro-debug/scenario-activation-send-smoke/maestro-output.txt`

## Addendum (P1.6 Preflight Reuse Contract Update)

Date: 2026-03-06  
Scope: Documented behavioral contract update only (no new lane execution in this note).

1. Real-runtime preflight now persists `model-sync-v1.json` on device under app media path with download fallback.
2. Lane runs retain mandatory provisioning instrumentation probe on every invocation.
3. `real-runtime-preflight.json` now records per-model sync decisions (`cache_hit`, `size_probe_hit`, `push_required`, `forced_sync`).
4. Follow-up rerun packet is required to attach concrete consecutive-run evidence for zero model `adb push` behavior.
