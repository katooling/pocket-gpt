# WP-09 Weekly UI Regression Matrix

Last updated: 2026-03-04  
Owner: QA Lead  
Cadence: Weekly (beta operations)

## Purpose

Define a repeatable weekly UI regression loop for WP-09 rollout operations, mapped to `UI-01..UI-10`, with promotion-facing pass/fail deltas and triage ownership.

## Device Cadence Policy

1. Required-tier lane (mandatory each week): physical Android required-tier device.
2. Best-effort lane (mandatory each week): best-effort execution lane.
3. If a best-effort physical device is unavailable, run a fallback best-effort host lane and log the hardware gap explicitly.

Current week hardware state (2026-03-04):

1. Required-tier physical device available: `DEVICE_SERIAL_REDACTED` (`SM_A515F`, Android 13).
2. Best-effort physical device available: No.
3. Best-effort fallback lane used: host regression lane + Maestro flow verification with caveat.

## UI Acceptance Mapping

| UI Flow | Regression Objective | Required-tier Lane | Best-effort Lane | Owner |
|---|---|---|---|---|
| UI-01 | Launch renders core chat surfaces | Instrumentation + Maestro | Maestro fallback | QA |
| UI-02 | Send action and response flow remain stable | Instrumentation + Maestro | Maestro fallback | QA + Eng |
| UI-03 | Session switch preserves session-local timeline | JVM ViewModel tests + instrumentation sanity | JVM tests | QA |
| UI-04 | Restart restores sessions and active context | JVM ViewModel tests | JVM tests | QA |
| UI-05 | Image attach success and invalid-file handling | JVM ViewModel tests + instrumentation sanity | JVM tests | QA + Eng |
| UI-06 | Tool success and schema rejection UX | JVM ViewModel tests + instrumentation checks | JVM tests | QA + Eng |
| UI-07 | Advanced controls routing override | Maestro + instrumentation | Maestro fallback | QA |
| UI-08 | Diagnostics export and redaction-safe UX | Instrumentation + Maestro | Maestro fallback | QA + Security |
| UI-09 | Offline policy remains enforced in active UI paths | policy regression reference + instrumentation launch sanity | policy regression reference | QA + Security |
| UI-10 | Long-run UI workflow stability (ANR/OOM/crash signal) | weekly soak packet reference + lane logs | fallback caveat note | QA |

## WP-13 UX Extensions (Weekly)

| UX Flow | Regression Objective | Required-tier Lane | Best-effort Lane | Owner |
|---|---|---|---|---|
| UX-ONBOARD-01 | First-run onboarding renders and can be completed/skipped | Instrumentation assertions | Instrumentation fallback | QA + Product |
| UX-MODEL-01 | Runtime status is visible and consistent (`Not ready`/`Loading`/`Ready`/`Error`) | Instrumentation assertions + advanced-sheet check | Instrumentation fallback | QA + Eng |
| UX-PRIVACY-01 | Privacy sheet opens and copy is visible | Instrumentation + Maestro sanity | Maestro fallback | QA + Product |
| UX-TOOL-01 | Natural-language tool prompt path executes deterministic result | Instrumentation + Maestro | Maestro fallback | QA + Eng |
| UX-SCENARIO-C-01 | Context follow-up flow remains stable and image entry point is visible | Instrumentation + Maestro (`scenario-c`) | Maestro fallback | QA + Product + Eng |

## Weekly Run Checklist

1. Record build id/commit/date.
2. Run `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`.
3. Run `python3 tools/devctl/main.py lane android-instrumented`.
4. Run `python3 tools/devctl/main.py lane maestro`.
5. Record best-effort device execution or explicit fallback/caveat.
6. Record explicit PASS/FAIL IDs for instrumentation and Maestro runs.
7. Record fail/pass deltas and assign owners for any failures.
8. Publish evidence note under `docs/operations/evidence/wp-09/`.

## Weekly Output Template

| Area | Status (`PASS`/`FAIL`/`CAVEAT`) | Delta vs Prior Week | Severity | Owner | Evidence |
|---|---|---|---|---|---|
| Chat/streaming |  |  |  |  |  |
| Session continuity |  |  |  |  |  |
| Image flow |  |  |  |  |  |
| Tool flow |  |  |  |  |  |
| Advanced controls |  |  |  |  |  |
| Diagnostics and safety UX |  |  |  |  |  |
| Stability signal |  |  |  |  |  |

## Release-Gate Integration

1. This matrix feeds QA release-promotion decisions in:
   - `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-release-promotion-checklist.md`
2. Any `S0`/`S1` from this matrix blocks promotion until triaged and dispositioned.
