# WP-09 QA-10 Evidence: Weekly UI Regression Matrix Run 01

Date: 2026-03-04  
Owner: QA Lead  
Task: `QA-10`  
Status: Done

## Objective

Execute week-01 UI regression matrix mapped to `UI-01..UI-10` and publish pass/fail outcomes with evidence paths for beta operations.

## Inputs

1. `docs/testing/wp-09-ui-regression-matrix.md`
2. `docs/testing/test-strategy.md`
3. `docs/testing/android-dx-and-test-playbook.md`
4. `docs/operations/ui-ux-handoff-ticket-pack.md`

## Device Coverage

1. Required-tier physical device: `RR8NB087YTF` (`SM_A515F`, Android 13) - executed.
2. Best-effort physical device: unavailable in current environment.
3. Best-effort fallback lane: host + Maestro workflow verification with explicit caveat.

## Commands and Outcomes

Run root:

- `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/eng-18-qa-10-ui-hardening-20260304-131620/`

1. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest`
   - Outcome: PASS
   - Artifact: `.../01-testDebugUnitTest.log`
2. `python3 tools/devctl/main.py lane android-instrumented`
   - Outcome: PASS
   - Artifact: `.../02-android-instrumented.log`
3. `python3 tools/devctl/main.py lane maestro`
   - Outcome: PASS
   - Artifact: `.../03-maestro.log`

## UI Matrix Status (`UI-01..UI-10`)

1. UI-01: PASS
2. UI-02: PASS
3. UI-03: PASS
4. UI-04: PASS
5. UI-05: PASS
6. UI-06: PASS
7. UI-07: PASS
8. UI-08: PASS
9. UI-09: PASS (policy regression references unchanged; no new bypass introduced)
10. UI-10: PASS for current lane coverage; best-effort physical-device class remains a tracked caveat.

## Deltas and Triage

1. New failures: none.
2. New caveats: one (`best-effort` physical-device hardware unavailable this week).
3. Owner for caveat closure: QA + Product (device pool expansion planning).

## Gate Decision

1. Week-01 QA-10 matrix execution is complete and evidence-linked.
2. Promotion impact: `green` with explicit best-effort hardware caveat.
