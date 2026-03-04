# User Journey Map (Install to Value)

Last updated: 2026-03-04

## Primary Persona

Privacy-sensitive Android user who wants useful AI help without cloud upload.

## Journey Stages

## 1. Discover

- Entry: Play Store listing or community referral.
- User question: "Is this truly local and useful?"
- UX requirement: clear privacy promise + real app proof (chat, tools, image).

## 2. Install and First Launch

- Entry: user opens app for first time.
- UX requirement: onboarding explains local-first behavior, core capabilities, and runtime readiness.
- Success signal: user can explain what is local, what the app can do, and what to do if models are not ready.

## 3. First Value Moment (Workflow A)

- User sends first text prompt.
- UX requirement: response appears quickly, streaming feels alive, runtime status is visible.
- Success signal: user receives useful answer with no confusion about state/errors.

## 4. Utility Expansion (Workflow B)

- User tries deterministic utility action (calculator/search/notes/reminder/date-time).
- UX requirement: natural-language tool invocation from chat plus deterministic result rendering.
- Success signal: tool outcome appears in-thread with no schema/policy ambiguity.

## 5. Continuity and Trust (Workflow C)

- User returns to previous sessions and follows up with image/text context.
- UX requirement: session persistence, restart continuity, clear privacy and diagnostics controls.
- Success signal: user can resume prior context and export redacted diagnostics if needed.

## Failure States That Must Be Explicit

1. Model not provisioned or runtime not ready.
2. Invalid image/tool input.
3. Runtime startup failure.
4. Offline policy enforcement rejection.

## MVP UX Completion Criteria

1. 5 non-technical testers complete Workflows A/B/C without moderator help.
2. First-session onboarding completion rate >= 80% in closed beta.
3. Median time-to-first-useful-answer < 60 seconds after first launch.
4. Reported confusion about runtime/model state < 10% of sessions.
