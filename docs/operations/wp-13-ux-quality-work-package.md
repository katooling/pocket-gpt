# WP-13 UX Quality Work Package

Last updated: 2026-03-05
Owner: Product
Support: Engineering, QA, Design, Marketing
Lifecycle: In Progress (`run-01` hold; moderated metrics pending)

## Objective

Close the gap between technical correctness and real first-session usability so promotion decisions are defensible.

## Scope

1. Onboarding and first-value flow quality.
2. Runtime/model status clarity and recovery.
3. Message readability and chat ergonomics.
4. Privacy comprehension and trust signaling.
5. Tool discoverability through natural language prompts.
6. Listing-ready proof assets tied to evidence-safe claims.

## Current Gate State

1. Technical lanes are passing on target device (`android-instrumented`, `maestro`, `journey`).
2. `run-01` packet decision is `hold` due missing moderated cohort metrics.
3. Pilot policy is active under single-build downloads + `soft gate` (`docs/operations/tickets/prod-09-soft-gate-pilot-policy.md`).

## Ticket Set (Current)

1. `UX-12` recovery story contract (`NotReady -> setup -> Ready`) - Done.
2. `UX-13` stuck send + timeout recovery UX contract - Ready.
3. `ENG-20` runtime cancel/timeout contract hardening - In Progress.
4. `QA-13` send-capture gate operationalization - Ready.
5. `QA-WP13-RUN02` moderated 5-user workflow execution - Ready.
6. `MKT-08` proof asset capture + listing shotlist finalization - Ready.
7. `PROD-10` launch gate matrix decision run - Ready.

## Usability Gate (Required for Broader Promotion)

Pass only if all are true:

1. 5 non-technical testers complete Workflow A/B/C without moderator help.
2. Onboarding completion >= 80%.
3. Recovery completion (`NotReady -> Ready`) >= 85%.
4. Runtime/model confusion <= 10%.
5. Privacy confusion <= 10%.
6. No open `UX-S0`/`UX-S1` blockers.

## Evidence Required

1. Filled packet from `docs/operations/wp-13-usability-gate-packet-template.md`.
2. Lane pass ids and report links for `android-instrumented`, `maestro`, `journey`.
3. `run_owner` and `run_host` metadata in journey artifacts.
4. Journey send-capture step values (`phase`, `elapsed_ms`, `runtime_status`, `backend`, `active_model_id`, `placeholder_visible`).
5. Timeout/cancel UX evidence (`UI-RUNTIME-001` mapping + recovery CTA path).
6. Qualitative synthesis mapped to PROD-08 taxonomy.
7. Listing-ready proof set mapped to `docs/operations/tickets/prod-10-launch-gate-matrix.md`.

## Active Blocker

1. Missing moderated 5-user packet metrics and session artifacts from `QA-WP13-RUN02`.
