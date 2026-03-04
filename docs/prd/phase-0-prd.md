# Phase 0 PRD - PocketAgent

## Objective

Build a privacy-first, offline-capable mobile AI assistant that runs locally on user devices with acceptable speed, battery behavior, and thermal stability for everyday use.

## Problem Statement

Users want useful AI assistance when:

- they do not trust cloud upload of personal data,
- connectivity is poor or unavailable,
- they need low-latency response on-device.

Most current products either depend on cloud inference or provide limited offline capability without strong daily utility.

## Target Users (ICP)

1. Privacy-sensitive professionals (journalists, founders, legal and healthcare-adjacent users)
2. Students and researchers needing offline summarization and Q&A
3. Travelers and field workers with intermittent connectivity
4. Developers and power users who value local control

## Jobs To Be Done (JTBD)

1. "Help me think and write quickly without sending my data to the cloud."
2. "Understand this image or document photo while offline."
3. "Execute simple assistant tools (notes, reminders, calculator) safely on my device."
4. "Remember relevant context from prior conversations without storing data remotely."

## Value Proposition

Private by architecture:

- local inference by default,
- explicit offline mode,
- no silent data upload,
- transparent model/runtime behavior.

## Product Principles

1. Local-first inference and storage
2. Explicit user control for any network-dependent action
3. Graceful degradation across device classes
4. Trustworthy, deterministic tool execution
5. Stable UX over peak benchmark numbers

## MVP Scope

Included:

1. Offline text chat with streaming responses
2. Single-image/document-photo understanding
3. 3-5 local tools (calculator, notes lookup, reminder creation, local search, date/time)
4. Model routing between `0.8B` and `2B` quantized tiers based on capability rules
5. Rolling context + summarization memory

## Launch Workflow Lock (PROD-01)

Status: Finalized lock pass on 2026-03-04 (WP-03 confirmed Done on execution board).

Top launch workflows (must be excellent at MVP launch):

1. Workflow A - Offline Quick Answer (text-only)
   - User intent: get a useful answer without network dependency.
   - Entry conditions: offline mode enabled or no connectivity.
   - Testable acceptance:
     - Scenario A threshold report = PASS on required launch devices.
     - Crash/OOM rate <= 5% across 10-run short loop.
     - No network call made during workflow execution (policy/log validation).
2. Workflow B - Offline Task Assist (safe local tools)
   - User intent: complete deterministic utility actions (calculator/date-time/notes/reminder) locally.
   - Entry conditions: supported tool request with allowlisted tool name and schema-valid payload.
   - Testable acceptance:
     - 100% rejection of malformed/non-allowlisted tool calls in tool safety test suite.
     - 100% deterministic success for positive allowlisted fixtures.
     - Zero policy bypass findings in adversarial tool regression tests.
3. Workflow C - Context Follow-up (memory + image-aware continuity)
   - User intent: ask follow-up questions with prior context and optional single-image input.
   - Entry conditions: prior chat context exists; optional image is provided.
   - Testable acceptance:
     - Scenario C benchmark evidence produced for required launch devices.
     - Memory survives app restart in persistence validation tests.
     - Quality rubric notes for image workflow recorded with no blocker defects.

Final lock gate for PROD-01:

1. `WP-03` status is `Done` on `docs/operations/execution-board.md`.
2. Workflow A/B/C evidence links are attached in the MVP beta go/no-go packet.
3. Product + QA both mark the launch workflow checklist as complete (PROD-03 handoff).

Excluded (non-MVP):

1. Broad video analysis workflows
2. On-device model training/fine-tuning (LoRA)
3. `4B`/`9B` default runtime guarantees on all phones
4. Autonomous multi-step open-ended agent loops
5. Cross-device sync and cloud backup by default

## Success Metrics (Phase 0 and MVP Entry)

Feasibility:

1. Stable operation on mid-tier devices without frequent OOM
2. Sustained thermal behavior in 5-10 minute sessions
3. Throughput and latency meet minimum UX thresholds

MVP readiness:

1. P50 first-token latency meets target
2. P50 decode throughput meets target
3. Crash-free benchmark sessions >= 95%
4. Image task completion quality meets acceptance rubric

## Assumptions That Must Hold

1. Quantized `0.8B` and `2B` models provide acceptable utility for key tasks
2. Local image pathway can run within thermal and memory limits
3. `llama.cpp` baseline can be integrated on iOS and Android within schedule
4. Packaging/downloading model artifacts is compatible with app-store constraints

## Constraints

1. Mobile RAM and thermal budgets are hard limits
2. Device fragmentation on Android requires fallback paths
3. App size and model distribution must be modular/on-demand
4. Privacy claims must match implementation details exactly

## Milestone Definition

Phase 0 complete when all of the following are true:

1. PRD and architecture documents approved
2. ADR set complete and accepted
3. Monorepo skeleton established
4. Benchmark protocol and device matrix finalized
5. Spike execution record produced with go/no-go recommendation
