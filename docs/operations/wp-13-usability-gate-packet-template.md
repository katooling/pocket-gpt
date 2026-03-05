# WP-13 Usability Gate Packet Template

Last updated: 2026-03-04
Owner: Product
Support: QA, Design, Engineering, Marketing

## Purpose

Operational template for WP-13 closure decisions. This packet complements technical gates with measurable usability outcomes.

## Cohort Metadata

1. Cohort id:
2. Build id + commit:
3. Device set used (required-tier + best-effort):
4. Session window (UTC):
5. Moderator(s):

## Task Script (Workflow A/B/C)

Each participant executes without intervention:

1. Workflow A - Offline quick answer
2. Workflow B - Local tool task
3. Workflow C - Context follow-up (with optional image)

Record:

- completion (`yes`/`no`)
- time-to-complete (seconds)
- blocker reason (if failed)

## Quantitative Gate Table

| Metric | Threshold | Actual | Pass |
|---|---|---|---|
| Workflow A completion (n=5+) | `>= 90%` |  |  |
| Workflow B completion (n=5+) | `>= 90%` |  |  |
| Workflow C completion (n=5+) | `>= 80%` |  |  |
| Onboarding completion | `>= 80%` |  |  |
| Runtime/model confusion reports | `<= 10%` |  |  |
| Privacy confusion reports | `<= 10%` |  |  |
| Critical UX blockers (`S0`/`S1`) | `0 open` |  |  |

## Qualitative Synthesis (PROD-08 Taxonomy)

For each category, provide top findings and owner:

1. usability
2. comprehension
3. reliability-perceived
4. performance-perceived
5. trust/privacy perception

## Evidence Links

1. QA weekly matrix run:
2. Real-runtime instrumentation journey run id:
3. Real-runtime Maestro journey run id:
4. User session notes:
5. Video/screenshot proof set:
6. Raw artifact root:

## Automation Attachments (Reusable)

Attach latest PASS ids and artifact paths for:

1. Real-runtime journey instrumentation (`RealRuntimeJourneyInstrumentationTest`)
2. Real-runtime app-path smoke (`RealRuntimeAppPathInstrumentationTest`, RC only)
3. Maestro Scenario A (`tests/maestro/scenario-a.yaml`)
4. Maestro Scenario B (`tests/maestro/scenario-b.yaml`)
5. Maestro Scenario C (`tests/maestro/scenario-c.yaml`)
6. Journey aggregate report (`journey-report.json` + `journey-summary.md`)

## Decision

1. Product recommendation (`promote`/`hold`):
2. QA concurrence (`yes`/`no`):
3. Engineering concurrence (`yes`/`no`):
4. Conditions to close WP-13 (if hold):
5. Decision date (UTC):
