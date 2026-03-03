# Product Playbook

Last updated: 2026-03-03

## Mission

Keep MVP scope focused, ensure user-value priority, maintain roadmap clarity, and drive cross-team decision quality.

## Where To Start

1. Check `docs/operations/execution-board.md` for milestone status.
2. Confirm product dependencies and acceptance criteria by stage.
3. Keep roadmap, feature catalog, and open questions current.

## What Is Done

- [x] Phase 0 PRD and MVP boundary documented
- [x] MVP stage plan, tracker, and go/no-go structure defined
- [x] Feature catalog and roadmap horizons documented

## What Is In Progress

- [ ] No active product task currently marked in-progress

## Product Dispatch Timing

1. Start now (draft/prep only):
   - draft `PROD-01` and `PROD-02` outputs with unresolved assumptions clearly marked
2. Finalize immediately after WP-03 is marked Done:
   - lock top launch workflows (`PROD-01`)
   - lock required vs best-effort device support policy (`PROD-02`)
3. After `PROD-01` is locked:
   - execute `PROD-03` final acceptance checklist consolidation with QA

## Immediate PM Dispatch

1. Use `docs/operations/evidence/wp-03/2026-03-03-pm-next-steps-dispatch.md` as the source for post-reconciliation next-owner assignments.
2. Do not re-dispatch completed C4-C7 work; focus on WP-03 closure gates and post-WP-03 package dispatch.

## Task Queue

| Task ID | Task | Status | Prerequisites | Owner | References |
|---|---|---|---|---|---|
| PROD-01 | Lock launch workflows (top 2-3 user journeys) | Ready | WP-03 | Product Lead | `docs/prd/phase-0-prd.md` |
| PROD-02 | Define launch device support policy (required vs best-effort) | Ready | WP-03 | Product Lead | `docs/feasibility/device-matrix.md` |
| PROD-03 | Finalize MVP acceptance checklist by stage | Ready | PROD-01 | Product + QA | `docs/roadmap/mvp-implementation-tracker.md` |
| PROD-04 | Monetization scope and pricing hypothesis | Backlog | PROD-01 | Product Lead | `docs/product/open-questions-log.md` |
| PROD-05 | Post-MVP voice product requirements (STT/TTS) | Backlog | WP-07 | Product Lead | `docs/roadmap/product-roadmap.md` |
| PROD-06 | Beta rollout process and user feedback loop | Backlog | WP-07, WP-08 | Product Ops | `docs/roadmap/mvp-beta-go-no-go-packet.md` |

## Product Definition of Done

1. Scope boundaries and non-goals are explicit.
2. Acceptance criteria are measurable and testable.
3. Dependencies are reflected on execution board.
4. Decisions and unresolved questions are documented.

## Product References

- `docs/operations/execution-board.md`
- `docs/roadmap/product-roadmap.md`
- `docs/product/feature-catalog.md`
- `docs/product/open-questions-log.md`
- `docs/roadmap/mvp-beta-go-no-go-packet.md`
