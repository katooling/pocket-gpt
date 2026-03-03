# PocketAgent Docs

This directory contains Phase 0 documentation for the product foundation, technical setup, and feasibility validation.

## Document Map

- `prd/phase-0-prd.md`: product framing, personas, JTBD, MVP boundaries
- `architecture/system-context.md`: constraints and high-level architecture context
- `architecture/modular-monolith.md`: module boundaries, interfaces, ownership
- `architecture/tech-stack-decision.md`: stack decision and alternatives
- `architecture/adrs/`: architecture decision records (ADR-001..005)
- `feasibility/device-matrix.md`: target devices and class definitions
- `feasibility/benchmark-protocol.md`: repeatable performance/quality tests
- `feasibility/spike-results.md`: Phase 0 spike execution record
- `feasibility/mvp-stage-execution-report.md`: MVP stage implementation and validation report
- `security/privacy-model.md`: privacy model and security controls
- `security/risk-register.md`: risk register with mitigations
- `testing/test-strategy.md`: repo-wide testing strategy and release gates
- `roadmap/phase-1-mvp-plan.md`: prioritized MVP implementation plan
- `roadmap/mvp-implementation-tracker.md`: stage-by-stage MVP execution tracker
- `roadmap/mvp-beta-go-no-go-packet.md`: beta decision packet template
- `roadmap/next-steps-execution-plan.md`: concrete next execution stages with DX/test gates
- `roadmap/product-roadmap.md`: short-, mid-, and long-term roadmap
- `roadmap/team-workstreams.md`: team ownership and independent workstreams
- `operations/README.md`: operating framework entrypoint
- `operations/execution-board.md`: single execution board with milestones and dependencies
- `operations/role-playbooks/`: role-based playbooks for Engineering, Product, Marketing, and QA
- `testing/android-dx-and-test-playbook.md`: Android testing and DX workflow
- `product/feature-catalog.md`: feature list by feasibility and horizon
- `product/open-questions-log.md`: active engineering/product/GTM questions

## Phase 0 Exit Package

Phase 0 is complete when:

1. PRD and architecture docs are approved.
2. ADR-001 to ADR-005 are accepted.
3. Monorepo skeleton and module boundaries are in place.
4. Benchmark protocol and device matrix are complete.
5. Feasibility spike execution record exists.
6. Risk/privacy documents are complete.
7. Phase 1 MVP plan is baselined.
