# MKT-07 UI Proof-Based Messaging Map

Last updated: 2026-03-04  
Owner: Marketing Lead  
Lifecycle: Approved for WP-09 sequencing

## Objective

Convert launch messaging to UI-proof claims only, where every user-facing line maps to validated in-app workflows and concrete evidence.

## Claim Governance

1. Every claim must map to validated UI flow evidence (`UI-01..UI-10`, ENG-18, QA-10).
2. Claims without evidence are marked `Provisional` or `Excluded`.
3. No claim may conflict with `docs/product/feature-catalog.md`.

## UI-Proof Claim Map

| MKT-07 Claim ID | User-Facing Claim | UI Flow Mapping | Evidence | Status |
|---|---|---|---|---|
| UIC-01 | Chat-first offline UX is visible at launch with clear composer/send controls. | UI-01, UI-02 | WP-11 QA-08 rerun + WP-09 ENG-18 run logs | Approved |
| UIC-02 | Session continuity controls (create/switch/delete) are present and persisted behavior is covered by tests. | UI-03, UI-04 | WP-11 evidence + ENG-18 unit coverage | Approved |
| UIC-03 | Image and tool flows include deterministic success/error UX with safety-focused messaging. | UI-05, UI-06 | ENG-18 ViewModel tests + UI error contract | Approved |
| UIC-04 | Advanced controls expose routing override and diagnostics action in-app. | UI-07, UI-08 | WP-11 + WP-09 QA-10 run-01 | Approved |
| UIC-05 | UI regression checks are executed weekly with evidence-linked artifacts. | UI-01..UI-10 ops loop | QA-10 matrix + run-01 evidence note | Approved |

## Excluded / Provisional Claims

### Excluded

1. iOS parity available now.
2. Voice mode/STT/TTS available now.
3. Universal performance guarantee for all Android classes.

### Provisional

1. Best-effort low-tier physical-device UX claim parity.
   - Rationale: current week lacks best-effort physical-device hardware; fallback lane used with explicit caveat.

## Messaging Snippets (Evidence-Safe)

1. `The current app experience is validated through weekly UI regression runs and evidence-linked QA checks.`
2. `Core chat, session continuity, image/tool actions, and advanced controls are verified against explicit UI acceptance flows.`
3. `Error handling is user-readable and safety-oriented, with deterministic support codes for triage.`
