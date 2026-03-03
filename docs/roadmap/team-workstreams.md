# Team Workstreams and Independent Execution

Last updated: 2026-03-03

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
| Android App | UI and lifecycle integration | chat UI loop, stage runner wiring, resilience behavior | runtime interfaces |
| Tools & Safety | tool schema/runtime + policy checks | strict validation, allowlist enforcement, abuse tests | tool/policy contracts |
| Memory & Multimodal | retention/retrieval + image path | SQLite memory backend, scenario C pipeline | memory/inference contracts |
| QA & Evidence | benchmarks, regression, artifacts | scenario runs, threshold reports, soak evidence | benchmark protocol |
| Product & GTM | roadmap, differentiation, launch prep | feature prioritization, messaging, distribution strategy | validated technical constraints |

## Interface Contracts (No Direct Coupling)

1. Runtime and Android app communicate via `InferenceModule`/`RoutingModule`.
2. Tool integration only through `ToolModule`.
3. Memory integration only through `MemoryModule`.
4. Policy checks must gate network-capable actions.

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
4. Scope creep threatening MVP timeline.
