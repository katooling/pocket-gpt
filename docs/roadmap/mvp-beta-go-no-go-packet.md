# MVP Beta Go/No-Go Packet

## Decision Date

2026-03-04

## Decision Owners and Signoff

1. Product Lead - final go/no-go decision owner
2. QA Lead - evidence quality and acceptance owner
3. Engineering Lead - technical readiness and blocker disposition owner

Signoff rule:

1. Packet is not final until all three owners sign with date and explicit `Go` or `No-Go`.
2. External beta/go-live signoff requires both `WP-07 = Done` and `WP-11 = Done`.

## Current Signoff State (2026-03-04)

1. Product Lead: `Go` (signed 2026-03-04)
2. QA Lead: `Go` (signed 2026-03-04; Stage-6 soak PASS evidence in `docs/operations/evidence/wp-07/2026-03-04-qa-06.md`)
3. Engineering Lead: `Go` (signed 2026-03-04; Stage-6 resilience closeout in `docs/operations/evidence/wp-07/2026-03-04-eng-stage6-resilience-closeout.md`)

WP-11 note (2026-03-04):

1. WP-11 rerun is complete and `UI-01`..`UI-10` evidence mapping is closed.
2. Latest rerun evidence: `docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md`.

## Scope

Android-first MVP with:

1. offline text chat
2. local tools
3. memory v1
4. single-image input
5. chat-first in-app UX with session persistence and advanced controls

## Go Criteria

1. Scenario A/B/C thresholds pass on target Android mid-tier devices.
2. No blocker privacy/security findings.
3. No repeatable OOM/ANR in soak tests.
4. Routing downgrade policy behaves correctly under stress.
5. UI acceptance suite (`UI-01`..`UI-10`) passes on release candidate app.

## No-Go Triggers

1. Sustained thermal throttling within first 5 minutes on target devices.
2. Frequent OOM or startup instability.
3. Tool validation bypass or unsafe execution path.
4. Offline-only policy fails to prevent network usage.
5. User-facing MVP flows (chat/session/image/tool/advanced-controls) fail acceptance suite.

## Evidence Checklist

- [x] benchmark CSV for scenarios A/B/C
- [x] threshold evaluation report
- [x] diagnostics export samples
- [x] soak test report
- [x] risk register review update
- [x] PROD-01 workflow lock evidence attached (A/B/C workflow acceptance checks)
- [x] PROD-02 required vs best-effort device policy table attached
- [x] PROD-03 finalization hook checklist completed with Product + QA + Engineering sign-off
- [x] WP-11 implementation foundation note attached
- [x] WP-11 UI acceptance evidence (`UI-01`..`UI-10`) attached

## UI Readiness Checklist (WP-11)

- [x] Chat timeline + streaming composer flow present in app UI.
- [x] Session list create/switch/delete UX present with persisted restore path.
- [x] Image attach action present and response rendered in-thread.
- [x] Local tool action UX present with deterministic result/error rendering.
- [x] Advanced controls sheet present (routing override + diagnostics export + runtime details).
- [x] Unit + runtime wiring tests updated for new UI contracts.
- [x] QA UI acceptance suite complete on physical-device lane.

## Stage Gate Status (PROD-03 Alignment)

### Stage 5 Gate (WP-06)

Status: Closed on 2026-03-04

Evidence links:

1. `docs/operations/evidence/wp-06/2026-03-04-eng-07-closeout.md`
2. `docs/operations/evidence/wp-06/2026-03-04-eng-08.md`
3. `docs/operations/evidence/wp-06/2026-03-04-qa-05.md`

### Stage 6 Gate (WP-07)

Status: Closed on 2026-03-04

Required criteria before final signoff:

1. 30-minute soak test PASS with artifact links.
2. Resilience guards and startup recovery checks PASS.
3. Unresolved blockers = 0, or explicit Product+QA risk acceptance documented.
4. Go/no-go decision date and owner signatures recorded.

Evidence links (current):

1. `docs/operations/evidence/wp-07/2026-03-04-qa-06.md`
2. `docs/operations/evidence/wp-04/2026-03-04-qa-03-rerun.md`
3. `docs/operations/evidence/wp-05/2026-03-04-eng-06-closeout.md`
4. `docs/operations/evidence/wp-07/2026-03-04-eng-stage6-resilience-closeout.md`
5. `docs/operations/evidence/wp-07/2026-03-04-prod-03-final-signoff.md`

### UI Gate (WP-11)

Status: Closed on 2026-03-04

Required criteria before final signoff:

1. UI acceptance suite (`UI-01`..`UI-10`) PASS on connected device.
2. Compose/instrumentation/Maestro lanes PASS.
3. Evidence note under `docs/operations/evidence/wp-11/` links raw run artifacts.
4. Product + QA + Engineering acknowledge UI gate closure.

Evidence links (current):

1. `docs/operations/evidence/wp-11/2026-03-04-eng-wp11-ui-foundation.md`
2. `docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md`
3. `tests/maestro/scenario-a.yaml`
4. `tests/maestro/scenario-b.yaml`

## Attached Policy and Workflow References

1. PROD-01 workflows: `docs/prd/phase-0-prd.md`
2. PROD-02 required vs best-effort policy table: `docs/feasibility/device-matrix.md`
3. WP-11 UI gate definition: `docs/roadmap/mvp-implementation-tracker.md`

## Open Risks and Owners

| Risk | Owner | Status | Mitigation |
|---|---|---|---|
| Qwen artifact packaging + checksum flow | Runtime | Open | finalize manifest + SHA pipeline |
| Android thermal variability by OEM | Android | Open | profile on at least 2 device classes |
| Tool safety edge cases | Platform | Open | maintain strict schema regression suite |
| Memory relevance quality | Core/AI | Open | improve retrieval scoring and summary shaping |
| UI acceptance gaps on lower-end devices | Android + QA | Open | execute UI-01..UI-10 on required and best-effort tiers |

## Recommendation

Current recommendation: **Go**.  
WP-07 and WP-11 gate requirements are both met; continue release sequencing through WP-09 operations.
