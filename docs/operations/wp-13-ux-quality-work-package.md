# WP-13 UX Quality Work Package

Last updated: 2026-03-04
Owner: Product
Support: Engineering, QA, Design, Marketing
Status: Ready

## Objective

Add a usability gate on top of technical correctness so MVP can ship with strong first-session UX.

## Scope

1. Onboarding and first-value flow quality.
2. Runtime/model status clarity.
3. Message readability and chat ergonomics.
4. Privacy comprehension and trust signaling.
5. Tool discoverability through natural language prompts.

## Ticket Set

1. `UX-ONBOARD-01` first-run onboarding flow.
2. `UX-MODEL-01` runtime/model loading status indicators.
3. `UX-CHAT-01` markdown rendering baseline for assistant output.
4. `UX-EMPTY-01` rich empty state with suggested prompts.
5. `UX-CHAT-02/03/04` copy action, auto-scroll, session naming polish.
6. `UX-PRIVACY-01` in-app privacy explanation screen.
7. `UX-TOOL-01` natural language tool invocation path.

## Usability Gate (Required for WP-13 closure)

Pass only if all are true:

1. 5 non-technical testers complete Workflow A/B/C without moderator help.
2. >= 80% onboarding completion in first session.
3. <= 10% tester-reported confusion on runtime/model state.
4. <= 10% tester-reported confusion on privacy behavior.
5. No blocker UI accessibility regressions in weekly matrix.

## Evidence Required

1. Usability run script and participant worksheet.
2. Completion-rate table and task drop-off notes.
3. Qualitative UX feedback synthesis (mapped to PROD-08 taxonomy).
4. Video/screenshot proof set for app-store and launch messaging.
5. Filled packet based on `docs/operations/wp-13-usability-gate-packet-template.md`.

## Quantitative Thresholds (Operational)

1. Workflow A completion >= 90% (minimum 5 participants).
2. Workflow B completion >= 90% (minimum 5 participants).
3. Workflow C completion >= 80% (minimum 5 participants).
4. Onboarding completion >= 80% in first session.
5. Runtime/model confusion reports <= 10%.
6. Privacy confusion reports <= 10%.
7. Open `S0`/`S1` UX blockers = 0 at decision point.

## Dependencies

1. `ENG-13` must be closed before publishing performance claims.
2. `QA-10` weekly regression cadence remains active.
3. `PROD-08` feedback taxonomy and synthesis template used as intake source.
