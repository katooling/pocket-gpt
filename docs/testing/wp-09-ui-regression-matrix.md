# WP-09 Weekly UI Regression Matrix

Last updated: 2026-03-08
Owner: QA Lead
Cadence: Weekly (beta operations)

## Purpose

Define a repeatable weekly UI regression loop mapped to current acceptance scope (`UI-01..UI-15`) with owner + lane accountability.

## Device Cadence Policy

1. Required-tier lane: mandatory weekly physical Android run.
2. Best-effort lane: mandatory weekly second-lane run (physical preferred, fallback documented).
3. Any fallback must be logged as a caveat in the weekly evidence note.

## UI Acceptance Mapping (`UI-01..UI-15`)

| UI Flow | Regression Objective | Required-tier Lane | Best-effort Lane | Owner |
|---|---|---|---|---|
| UI-01 | Launch renders chat + runtime indicators | instrumentation + Maestro | Maestro fallback | QA |
| UI-02 | Send/stream/finalization remains stable | instrumentation + Maestro scenario A | Maestro fallback | QA + Eng |
| UI-03 | Session switch preserves per-session timeline | JVM + instrumentation sanity | JVM fallback | QA |
| UI-04 | Restart restores sessions and active context | JVM + instrumentation sanity | JVM fallback | QA |
| UI-05 | Image attach success/invalid handling | JVM + instrumentation | JVM fallback | QA + Eng |
| UI-06 | Tool success/schema rejection UX | JVM + instrumentation + Maestro scenario B | JVM fallback | QA + Eng |
| UI-07 | Routing controls update runtime mode | instrumentation + Maestro scenario B | Maestro fallback | QA |
| UI-08 | Diagnostics export + redaction-safe UX | instrumentation + Maestro scenario B | Maestro fallback | QA + Security |
| UI-09 | Offline policy stays enforced | policy tests + instrumentation startup | policy tests fallback | QA + Security |
| UI-10 | Long-run UI stability (ANR/OOM/crash) | soak packet + lane logs | fallback caveat | QA |
| UI-11 | Timeout/cancel recovery exits sending state | journey strict + instrumentation | valid-output fallback | QA + Eng |
| UI-12 | Manifest outage keeps import recovery path visible | instrumentation provisioning flow | instrumentation fallback | QA + Eng |
| UI-13 | Performance profile controls persist and apply | JVM + instrumentation + Maestro scenario B | JVM/instrumentation fallback | QA + Eng |
| UI-14 | GPU toggle reflects capability and persistence | instrumentation + Maestro scenario B | instrumentation fallback | QA + Eng |
| UI-15 | Runtime telemetry labels appear after send | instrumentation + journey report fields | instrumentation fallback | QA + Eng |

## WP-13 UX Extensions (Weekly)

| UX Flow | Regression Objective | Required-tier Lane | Best-effort Lane | Owner |
|---|---|---|---|---|
| UX-ONBOARD-01 | Onboarding render + complete/skip path | instrumentation | instrumentation fallback | QA + Product |
| UX-MODEL-01 | Runtime state readability (`Not ready`/`Loading`/`Ready`/`Error`) | instrumentation + advanced-sheet checks | instrumentation fallback | QA + Eng |
| UX-MODEL-02 | Recovery flow (download/import -> activate -> refresh) | instrumentation provisioning journey + first-run Maestro flow | instrumentation fallback | QA + Eng + Product |
| UX-PRIVACY-01 | Privacy sheet visibility/copy | instrumentation + Maestro | Maestro fallback | QA + Product |
| UX-SCENARIO-C-01 | Continuity/image-aware journey stability | instrumentation + Maestro scenario C | Maestro fallback | QA + Product + Eng |

## Weekly Run Checklist

1. Record build id, commit, device(s), and run date.
2. Run `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`.
3. Run `python3 tools/devctl/main.py lane android-instrumented`.
4. Run `python3 tools/devctl/main.py lane maestro`.
5. Run `python3 tools/devctl/main.py lane journey --repeats 1 --mode strict --steps instrumentation,send-capture`.
6. Run `python3 tools/devctl/main.py lane screenshot-pack` and complete manual screenshot review.
7. Run `python3 tools/devctl/main.py governance screenshot-inventory-check`.
8. Record PASS/FAIL/CAVEAT with evidence links under `docs/operations/evidence/wp-09/`.

## Weekly Output Template

| Area | Status (`PASS`/`FAIL`/`CAVEAT`) | Delta vs Prior Week | Severity | Owner | Evidence |
|---|---|---|---|---|---|
| Chat/streaming |  |  |  |  |  |
| Session continuity |  |  |  |  |  |
| Image flow |  |  |  |  |  |
| Tool flow |  |  |  |  |  |
| Advanced/runtime controls |  |  |  |  |  |
| Recovery + timeout semantics |  |  |  |  |  |
| Screenshot regression review |  |  |  |  |  |
| Stability signal |  |  |  |  |  |

## Release-Gate Integration

1. This matrix feeds `docs/operations/tickets/prod-10-launch-gate-matrix.md` inputs.
2. Any `S0`/`S1` from weekly matrix blocks promotion until resolved or explicitly waived.
