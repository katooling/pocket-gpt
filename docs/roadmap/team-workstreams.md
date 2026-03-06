# Team Workstreams

Last updated: 2026-03-06

This document defines stable workstream ownership boundaries.

For live task status and ordering, use `docs/operations/execution-board.md`.

## Workstream Map

| Workstream | Primary Scope | Key Interfaces | Depends On |
|---|---|---|---|
| Product Ops | release policy, decision governance, docs coherence | `docs/operations/tickets/prod-09-soft-gate-pilot-policy.md`, `docs/operations/tickets/prod-10-launch-gate-matrix.md` | execution board + evidence index |
| Runtime + DX | lane robustness, runtime contracts, deterministic preflight/recovery | `docs/testing/test-lane-profiles-and-selection.md`, `docs/operations/tickets/eng-20-runtime-cancel-timeout-contract.md` | devctl lanes + runtime bridge contracts |
| QA Operations | lane execution, packet closure, weekly regression signal | `docs/testing/android-dx-and-test-playbook.md`, `docs/operations/tickets/qa-13-send-capture-gate-operationalization.md` | runtime lane stability + ticket acceptance contracts |
| UX/Product Design | first-run recovery and timeout/cancel comprehension | `docs/ux/model-management-flow.md`, `docs/operations/tickets/ux-13-stuck-send-timeout-recovery.md` | runtime state model + moderated usability packet |
| Marketing Ops | evidence-safe proof assets and channel execution | `docs/operations/tickets/mkt-08-proof-asset-capture-and-listing-finalization.md`, `docs/operations/tickets/mkt-09-channel-scorecard-run-01.md` | launch gate matrix + privacy parity audit |
| Security + Trust | privacy claim parity and controls mapping | `docs/security/privacy-model.md`, `docs/operations/tickets/sec-02-privacy-claim-parity-audit.md` | QA evidence + product claim governance |

## Interface Contracts

1. Launch decision interface is `docs/operations/tickets/prod-10-launch-gate-matrix.md`.
2. Usability packet evidence requirements are defined in `docs/operations/wp-13-usability-gate-packet-template.md`.
3. Publishable claims must map to evidence IDs and verified privacy parity rows.
4. Runtime/app integration contract remains through `MvpRuntimeFacade`.
