# Product Playbook

Last updated: 2026-03-04

## Mission

Keep MVP scope focused, ensure user-value priority, maintain roadmap clarity, and drive cross-team decision quality.

## Where To Start

1. Check `docs/operations/execution-board.md` for milestone status.
2. Confirm product dependencies and acceptance criteria by stage and package.
3. Keep roadmap, feature catalog, and open questions current.

## What Is Done

- [x] Phase 0 PRD and MVP boundary documented
- [x] MVP stage plan, tracker, and go/no-go structure defined
- [x] Feature catalog and roadmap horizons documented
- [x] PROD-01 launch workflow lock finalized (2026-03-04)
- [x] PROD-02 launch device policy lock finalized (2026-03-04)

## What Is In Progress

- [x] PROD-03 Stage 6 final acceptance packet in progress
- [x] PROD-07 WP-11 UI story + acceptance-gate governance in progress

## Product Dispatch Timing

1. Keep Stage-6 packet moving (WP-07 owners: QA/Product/Engineering).
2. In parallel, keep WP-11 UI package aligned to canonical user stories and acceptance gates.
3. Final external beta decision only after both `WP-07` and `WP-11` are `Done`.

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| PROD-01 | Lock launch workflows (top 2-3 user journeys) | Done | WP-03 | Product Lead | `docs/prd/phase-0-prd.md` |
| PROD-02 | Define launch device support policy (required vs best-effort) | Done | WP-03 | Product Lead | `docs/feasibility/device-matrix.md` |
| PROD-03 | Finalize MVP acceptance checklist by stage | In Progress | PROD-01 | Product + QA + Engineering | `docs/roadmap/mvp-implementation-tracker.md` |
| PROD-07 | WP-11 UI story and acceptance governance | In Progress | WP-06 | Product Lead | `docs/prd/phase-0-prd.md`, `docs/roadmap/mvp-implementation-tracker.md` |
| PROD-04 | Monetization scope and pricing hypothesis | Backlog | PROD-01 | Product Lead | `docs/product/open-questions-log.md` |
| PROD-05 | Post-MVP voice product requirements (STT/TTS) | Backlog | WP-07 | Product Lead | `docs/roadmap/product-roadmap.md` |
| PROD-06 | Beta rollout process and user feedback loop | Backlog | WP-07, WP-08, WP-11 | Product Ops | `docs/roadmap/mvp-beta-go-no-go-packet.md` |

## Product Definition of Done

1. Scope boundaries and non-goals are explicit.
2. Acceptance criteria are measurable and testable.
3. Dependencies are reflected on execution board.
4. Decisions and unresolved questions are documented.

## Product References

- `docs/operations/execution-board.md`
- `docs/prd/phase-0-prd.md`
- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/roadmap/mvp-beta-go-no-go-packet.md`
- `docs/product/feature-catalog.md`
- `docs/product/open-questions-log.md`
