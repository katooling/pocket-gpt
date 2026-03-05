# WP-13 QA Evidence: Wireless Device Lane Rerun (Android Instrumented + Maestro)

Date: 2026-03-05  
Owner: Engineering + QA  
Device: `adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp` (`SM-A515F`, Android 13)

## Scope

Re-run previously blocked device lanes after wireless-debug attachment:

1. `python3 tools/devctl/main.py lane android-instrumented`
2. `python3 tools/devctl/main.py lane maestro`

## Commands and Outcomes

1. `ADB_SERIAL='adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp' python3 tools/devctl/main.py lane android-instrumented`
   - Result: PASS (`BUILD SUCCESSFUL`; connected instrumentation completed, `0 failed`, RC-only classes skipped by explicit gating flags).
2. `ADB_SERIAL='adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp' python3 tools/devctl/main.py lane maestro`
   - Result: PASS (Scenario A/B/C flows all completed; no runtime error banner assertions failed).

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

## Notes

1. Wireless runs were coordinated using per-device lock files under:
   - `scripts/benchmarks/device-env/locks/`
2. Lane robustness updates validated during this rerun:
   - remote media path preflight no longer depends on fragile `sh -lc` quoting
   - `lane maestro` now ensures test APK install before provisioning instrumentation probe

