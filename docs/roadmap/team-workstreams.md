# Team Workstreams and Independent Execution

Last updated: 2026-03-05

This document defines parallel workstreams that map directly to the rebased release ticket set.

Role playbooks:

- `docs/operations/role-playbooks/engineering-playbook.md`
- `docs/operations/role-playbooks/product-playbook.md`
- `docs/operations/role-playbooks/marketing-playbook.md`
- `docs/operations/role-playbooks/qa-playbook.md`

## Workstream Map

| Workstream | Primary Scope | Current Focus Tickets | Depends On |
|---|---|---|---|
| Product Ops | release policy, docs coherence, decision governance | `DOC-01`, `DOC-02`, `PROD-09`, `PROD-10`, `PROD-11` | execution board + tracker + evidence contracts |
| Runtime + DX | lane robustness, deterministic preflight/recovery behavior | `ENG-19`, `ENG-20` | devctl lane architecture + runtime bridge contracts |
| QA Operations | lane reruns, packet closure, weekly regression signal | `QA-11`, `QA-13`, `QA-WP13-RUN02`, `QA-12` | ENG-19/ENG-20 + WP-13 packet template |
| UX/Product Design | first-run recovery and timeout/cancel comprehension | `UX-12`, `UX-13` | model-management flow + runtime states |
| Marketing Ops | evidence-safe proof assets and channel experiment execution | `MKT-08`, `MKT-09`, `MKT-10` | moderated packet + launch gate matrix + SEC-02 |
| Security + Trust | privacy-claim parity and enforcement evidence mapping | `SEC-02` | privacy model + diagnostics evidence |

## Interface Contracts

1. Product decision interface is `docs/operations/prod-10-launch-gate-matrix.md`.
2. WP-13 packet interface requires `run_owner`, `run_host`, and lane pass IDs.
3. Publishable marketing claims must map to evidence IDs and gate-matrix rows.
4. Runtime/app integration remains through `MvpRuntimeFacade`; no direct coupling to runtime internals.

## Active Ticket Ownership (March 6-15)

| Ticket | Owner | Support | Status |
|---|---|---|---|
| DOC-01 | Product Ops | Eng, QA, Marketing | In Progress |
| DOC-02 | Product Lead | Eng, QA, Design | In Progress |
| ENG-19 | Engineering | QA | In Progress |
| ENG-20 | Engineering | QA, Product | In Progress |
| QA-11 | QA | Engineering | Ready |
| QA-13 | QA | Engineering, Product | Ready |
| PROD-09 | Product Ops | QA, Marketing | Done |
| UX-12 | Product + Design + Android | QA | Done |
| UX-13 | Product + Design + Android | QA | Ready |
| QA-WP13-RUN02 | QA + Product | Design, Engineering | Ready |
| MKT-08 | Marketing | Product | Ready |
| MKT-09 | Marketing | Product, QA | Ready |
| MKT-10 | Marketing | Product, Security | Ready |
| SEC-02 | Product + Security | QA, Marketing | Ready |
| PROD-11 | Product Ops | QA, Marketing | Ready |
| PROD-10 | Product | QA, Eng, Marketing | Ready |

## Weekly Decision Questions

### UI/UX

1. What first-run setup time is acceptable for launch (P50/P90)?
2. Which recovery copy still causes confusion in moderated sessions?

### Backend/Runtime

1. Which preflight failures should hard-fail vs warn-and-continue?
2. What is the fallback behavior if internal manifest fetch is empty/unreachable?

### Marketing

1. Which claim blocks are evidence-safe for week-1 publishing?
2. Which proof asset set is mandatory before broader promotion?

### Product/Ops

1. What pilot size/duration triggers promote vs hold under soft gate?
2. Which metrics are mandatory versus advisory for expansion?

## Definition of Done (Per Ticket)

1. Deliverable updated in source-of-truth docs or code.
2. Acceptance criteria met and evidence linked.
3. Status reflected in `docs/operations/execution-board.md`.
4. Relevant role playbook updated.
