# UI/UX Handoff Ticket Pack

Last updated: 2026-03-04
Owner: Product Lead
Status: Ready for dispatch

## Purpose

This document defines zero-context tickets for the next UI/UX wave after `WP-11` closure.  
All tickets follow the operations framework and must be tracked in `docs/operations/execution-board.md` first, then mirrored in role playbooks.

## Required Operating Rules

1. Start from `docs/operations/README.md`.
2. Use `docs/operations/execution-board.md` as source of truth.
3. Only take tasks in `Ready` with prerequisites met.
4. No task is `Done` without tests + evidence + docs status sync.
5. Commands must follow `scripts/dev/README.md`.
6. Test/release gates must follow `docs/testing/test-strategy.md`.
7. Device execution must follow `docs/testing/android-dx-and-test-playbook.md`.

## Ticket Set

## ENG-18 - UI Accessibility + Error-State Hardening

- Work package: `WP-09` support track
- Owner: Engineering (Android)
- Status: Ready
- Prerequisites: `WP-11 Done`

### Context

`WP-11` delivered core chat UX. Before wider beta expansion, UI behavior needs accessibility coverage and deterministic failure UX polish so support load stays manageable.

### Scope

1. Add accessibility labels/semantics for primary chat surfaces (composer, send, session switcher, image attach, tool actions, advanced sheet).
2. Standardize user-readable error states for model startup failure, image validation failure, and tool schema rejection.
3. Add UI performance guardrails for long thread rendering and streaming updates (no jank regressions in common flows).

### Deliverables

1. Code + tests for accessibility semantics and error-state rendering.
2. Updated docs where user-visible behavior changed.
3. Evidence note under `docs/operations/evidence/wp-09/`.

### Acceptance

1. `:apps:mobile-android:testDebugUnitTest` passes with new UI tests.
2. `:apps:mobile-android:connectedDebugAndroidTest` passes with updated assertions.
3. Evidence links include raw artifacts under `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...`.

### Dispatch Prompt

Follow the ops framework. Execute `ENG-18` (UI accessibility + error-state hardening) on top of `WP-11`. Add semantics for key controls, unify user-facing failure messages, and add tests for both happy/error paths. Run required unit + instrumentation lanes, publish evidence under `docs/operations/evidence/wp-09/`, and sync status in execution board + engineering playbook.

## QA-10 - UI Rollout Regression Matrix (Beta Ops)

- Work package: `WP-09`
- Owner: QA
- Status: Ready
- Prerequisites: `WP-11 Done`, `QA-08 Done`

### Context

Current UI gate is closed, but rollout needs repeatable weekly UI regression checks tied to beta operations.

### Scope

1. Define weekly UI regression matrix across required/best-effort device classes.
2. Run regression loops for chat/session/image/tool/advanced-controls.
3. Track pass/fail deltas and severity trends for promotion decisions.

### Deliverables

1. Regression matrix doc + checklist.
2. First weekly run evidence packet with failures triaged (if any).
3. Updated QA playbook references for ongoing cadence.

### Acceptance

1. Matrix includes mapping to `UI-01..UI-10`.
2. Run output contains command logs + device IDs + outcomes.
3. Evidence note and raw artifacts paths are linked and valid.

### Dispatch Prompt

Follow the ops framework. Execute `QA-10` for WP-09: create and run the weekly UI regression matrix mapped to `UI-01..UI-10` across required and best-effort devices. Capture commands, outcomes, failures, and triage ownership in evidence under `docs/operations/evidence/wp-09/`, with raw outputs under `scripts/benchmarks/runs/...`. Update execution board + QA playbook status.

## PROD-08 - UX Feedback Taxonomy + Intake Policy

- Work package: `WP-09`
- Owner: Product
- Status: Ready
- Prerequisites: `WP-11 Done`, `PROD-06 In Progress`

### Context

Distribution can scale only if UX feedback is categorized consistently for prioritization and release gating.

### Scope

1. Define UX issue taxonomy (usability, comprehension, reliability-perceived, performance-perceived, trust/privacy perception).
2. Define beta intake form and SLA rules for triage and response.
3. Map taxonomy to board/status workflow and severity thresholds.

### Deliverables

1. Product ops doc for UX feedback intake + taxonomy.
2. Weekly synthesis template for engineering + QA consumption.
3. Evidence note with first dry-run or pilot usage.

### Acceptance

1. Taxonomy categories are unambiguous and action-oriented.
2. Handoff includes owner mapping for each category.
3. References are added to product playbook and WP-09 execution path.

### Dispatch Prompt

Follow the ops framework. Execute `PROD-08` under WP-09 by defining a UX feedback taxonomy, intake policy, and severity-to-owner mapping for beta operations. Ensure artifacts are actionable for Eng/QA triage and release promotion decisions. Publish evidence and sync board + product playbook.

## MKT-07 - UI Proof-Based Messaging + Asset Selection

- Work package: `WP-09`
- Owner: Marketing
- Status: Ready
- Prerequisites: `WP-11 Done`, `MKT-04 In Progress`

### Context

Messaging now needs to align to validated in-app UI flows, not just engine capability claims.

### Scope

1. Update messaging map with UI-proof anchors (chat flow, session continuity, image/tool UX, advanced controls).
2. Define approved screenshot/video shot list tied to validated flows only.
3. Mark excluded claims and provisional claims with explicit rationale.

### Deliverables

1. Revised messaging map and capture list.
2. Asset readiness checklist tied to evidence IDs.
3. Evidence note for review/approval pass.

### Acceptance

1. Every user-facing claim maps to a validated UI flow and evidence.
2. No claim conflicts with `docs/product/feature-catalog.md`.
3. Publish-readiness checklist updated with exclusions/provisional flags.

### Dispatch Prompt

Follow the ops framework. Execute `MKT-07` by converting current launch messaging and media selection to UI-proof-based claims only. Use validated WP-11 evidence as source, produce an approved shot list, and flag excluded/provisional claims. Update execution board + marketing playbook and publish evidence links.

