# Team Workstreams and Independent Execution

Last updated: 2026-03-04

This document defines parallel workstreams so multiple team members can execute independently while staying aligned to shared contracts.

Role playbooks:

- `docs/operations/role-playbooks/engineering-playbook.md`
- `docs/operations/role-playbooks/product-playbook.md`
- `docs/operations/role-playbooks/marketing-playbook.md`
- `docs/operations/role-playbooks/qa-playbook.md`

## Workstream Map

| Workstream | Primary Scope | Core Deliverables | Depends On |
|---|---|---|---|
| Runtime | model loading/generation/routing | real `llama.cpp` bridge, routing matrix, perf metrics | inference contracts |
| Android App UX (`WP-11`) | chat-first app surface + lifecycle integration | Compose chat timeline, session UX, image/tool UX, advanced controls | runtime facade contracts |
| Tools & Safety | tool schema/runtime + policy checks | strict validation, allowlist enforcement, abuse tests | tool/policy contracts |
| Memory & Multimodal | retention/retrieval + image path | shared file-backed memory backend, scenario C pipeline | memory/inference contracts |
| QA & Evidence | benchmarks, regression, artifacts | scenario runs, threshold reports, soak evidence, UI acceptance evidence | benchmark protocol + UX acceptance matrix |
| Product & GTM | roadmap, differentiation, launch prep | feature prioritization, messaging, distribution strategy, go/no-go package | validated technical constraints |

## Interface Contracts (No Direct Coupling)

1. Android App UX talks to runtime through `MvpRuntimeFacade` only.
2. Runtime internals remain behind `InferenceModule`/`RoutingModule`/`ToolModule`/`MemoryModule`/`PolicyModule` contracts.
3. Tool integration remains through `ToolModule`; no direct shell/system execution path.
4. Policy checks remain authoritative for network/data-boundary behavior.

## Active Work Package Ownership

| Package/Task | Owner | Support | Status |
|---|---|---|---|
| WP-07 / QA-06 + packet closure | QA Lead | Eng + Product | In Progress |
| WP-11 / ENG-10 (Compose UI + facade + persistence) | Runtime Eng | Product + QA | In Progress |
| WP-11 / QA-08 (UI acceptance suite) | QA Engineer | Eng + Product | Ready |
| WP-11 / PROD-07 (UI stories + UX gate governance) | Product Lead | Eng + QA | In Progress |

## Sprint Structure (Suggested)

1. Weekly planning around stage goals.
2. Mid-week checkpoint on risk and blockers.
3. End-week demo with evidence artifacts (not only code diffs).

## Definition of Done (Per Task)

1. Code or document deliverable complete.
2. Tests updated and passing.
3. Evidence artifacts produced where required.
4. Tracker docs updated:
   - `docs/roadmap/mvp-implementation-tracker.md`
   - `docs/roadmap/next-steps-execution-plan.md`

## Escalation Triggers

1. Missed benchmark threshold on target device class.
2. Repeated OOM/ANR not solved within one sprint.
3. Security/privacy control ambiguity.
4. UI acceptance suite failures in core user workflows.
5. Scope creep threatening MVP timeline.
