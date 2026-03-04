# WP-08 Evidence Note: MKT Lock Pass (MKT-01 + MKT-02)

Date: 2026-03-04
Work package: WP-08
Owner: Marketing / Product Marketing
Status: Done (lock pass for messaging + competitor matrix)

## Scope Executed

1. Finalized MKT-01 messaging architecture with explicit claim labels (`Validated`/`Provisional`/`Excluded`).
2. Finalized MKT-02 competitor matrix with publishability guardrails and dependency-tagged risk labeling.
3. Synced status in execution board and marketing playbook.

## Inputs Reviewed

1. `docs/operations/role-playbooks/marketing-playbook.md`
2. `docs/operations/execution-board.md`
3. `docs/product/feature-catalog.md`
4. `docs/product/open-questions-log.md`
5. `docs/operations/evidence/wp-03/2026-03-04-qa-02-closeout.md`

## Artifacts Produced/Updated

1. `docs/operations/mkt-01-messaging-architecture-draft.md` (finalized lock state)
2. `docs/operations/mkt-02-competitor-matrix-template-draft.md` (finalized evidence-safe matrix)
3. `docs/operations/role-playbooks/marketing-playbook.md` (status sync)
4. `docs/operations/execution-board.md` (status sync + evidence log)

## Validation Summary

1. Publishable set restricted to `Validated` claims only.
2. Provisional claims explicitly tagged with pending dependencies:
   - `DEP-WP06-STAGE5` (Scenario C/memory quality)
   - `DEP-WP07-STAGE6` (soak/go-no-go)
3. Excluded claims explicitly barred from MVP messaging:
   - iOS parity available now
   - voice/STT/TTS available now
   - universal device performance guarantee

## Decision

MKT-01 and MKT-02 lock passes are complete for post-WP-03 messaging governance.
Any net-new public claim requires evidence update and re-label before publish.
