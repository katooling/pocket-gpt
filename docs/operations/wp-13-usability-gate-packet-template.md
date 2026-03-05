# WP-13 Usability Gate Packet Template

Last updated: 2026-03-05
Owner: Product
Support: QA, Design, Engineering, Marketing

## Purpose

Operational template for WP-13 closure and promotion decisions.
This packet complements technical gates with moderated usability outcomes.

## Cohort Metadata

1. Cohort id:
2. Build id + commit:
3. Build variant/build id details:
4. Device set used (required-tier + best-effort):
5. Session window (UTC):
6. Moderator(s):
7. Run owner (`run_owner`):
8. Run host (`run_host`):

## Lane Pass IDs (Required)

1. `android-instrumented` pass id:
2. `maestro` pass id:
3. `journey` pass id:
4. Journey report path (`journey-report.json`):
5. Journey summary path (`journey-summary.md`):
6. Latest send-capture step phase (`completed` required):
7. Latest send-capture elapsed (ms):
8. Latest send-capture runtime status/backend/model:
9. Placeholder visible at SLA checkpoint (`false` required):
10. Response visible at SLA checkpoint (`true` required):
11. Response role + non-empty (`assistant`/`system`, `true` required):
12. First token seen before completion (`true` for happy path):
13. Request id / finish reason / terminal event seen (`request_id`, `finish_reason`, `terminal_event_seen=true`):
14. First token / completion timing fields (`first_token_ms`, `completion_ms`):
15. Timeout/cancel UX code observed (expected `UI-RUNTIME-001` on timeout paths):
16. Timeout recovery CTA path verified (`retry`, `refresh`, `fix model setup`):

## Task Script (Workflow A/B/C)

Each participant executes without intervention:

1. Workflow A - Offline quick answer.
2. Workflow B - Local tool task.
3. Workflow C - Context follow-up (optional image).

Record per participant:

1. completion (`yes`/`no`)
2. time-to-complete (seconds)
3. blocker reason (if failed)
4. confusion notes (runtime/model/privacy)

## Quantitative Gate Table

| Metric | Threshold | Actual | Pass |
|---|---|---|---|
| Workflow A completion (n=5+) | `>= 90%` |  |  |
| Workflow B completion (n=5+) | `>= 90%` |  |  |
| Workflow C completion (n=5+) | `>= 80%` |  |  |
| Onboarding completion | `>= 80%` |  |  |
| Recovery completion (`NotReady -> Ready`) | `>= 85%` |  |  |
| Runtime/model confusion reports | `<= 10%` |  |  |
| Privacy confusion reports | `<= 10%` |  |  |
| Critical UX blockers (`S0`/`S1`) | `0 open` |  |  |

## UX Event Evidence (Recovery Contract)

Include sampled rows or export references for:

1. `onboarding_completed`
2. `runtime_not_ready_visible`
3. `model_setup_opened`
4. `model_import_started`
5. `model_download_started`
6. `model_version_activated`
7. `runtime_ready`
8. `first_useful_answer_ms`

## Qualitative Synthesis (PROD-08 Taxonomy)

For each category, provide top findings and owner:

1. usability
2. comprehension
3. reliability-perceived
4. performance-perceived
5. trust/privacy perception

## Evidence Links

1. QA weekly matrix run:
2. User session notes:
3. Video/screenshot proof set:
4. Raw artifact root:
5. Claim-map row ids impacted (`PROD-10`):

## Soft-Gate Decision Inputs

1. Pilot cohort size:
2. Pilot duration:
3. Hard-stop triggered (`yes`/`no`):
4. If yes, blocker + owner + ETA:
5. Recommendation scope (`promote`/`iterate`/`hold`):

## Decision

1. Product recommendation (`promote`/`iterate`/`hold`):
2. QA concurrence (`yes`/`no`):
3. Engineering concurrence (`yes`/`no`):
4. Marketing concurrence (`yes`/`no`):
5. Conditions to close WP-13 (if not promote):
6. Decision date (UTC):
