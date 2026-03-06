# Risk Register (Phase 0)

| ID | Risk | Severity | Likelihood | Mitigation | Owner |
|---|---|---|---|---|---|
| R-001 | Mid-tier devices fail memory targets with 2B | High | Medium | Default 0.8B on constrained devices, enforce routing caps | AI Runtime |
| R-002 | Thermal throttling degrades UX in sustained sessions | High | High | Session-aware token caps, thermal downgrade policy | Mobile Platform |
| R-003 | Android acceleration path fragmented by OEM drivers | High | High | Keep robust baseline runtime fallback, device qualification list | Android |
| R-004 | iOS conversion path blocks optimization timeline | Medium | Medium | Keep baseline runtime for launch, isolate optimization track | iOS |
| R-005 | Privacy claims diverge from implementation | Critical | Medium | PolicyModule enforcement, privacy test checklist, docs audits | Security |
| R-006 | Tool injection/execution abuse | Critical | Medium | strict JSON schema, allowlist tools, no shell execution | Platform |
| R-007 | Model download/package strategy fails app-store constraints | High | Medium | on-demand model packs, preflight checks, progressive downloads | Product/Platform |
| R-008 | Benchmark protocol inconsistency across testers | Medium | Medium | fixed scenario templates and standardized run environment | QA |
| R-009 | MVP scope creep into non-MVP features | Medium | High | explicit non-goals and backlog gate review | Product |
| R-010 | Legal/licensing assumptions change | High | Low | periodic license review and release checklist | Product/Legal |

## Risk Review Cadence

1. Weekly review during Phase 0 and Phase 1.
2. Track mitigation status and residual risk.
3. Update go/no-go recommendation with unresolved high risks.

## Stage-6 Review Update (2026-03-04)

Evidence reviewed:

1. `docs/operations/evidence/index.md` (WP-04..WP-07 historical summaries)
2. `docs/operations/execution-board.md`

Risk posture notes:

1. `R-002` (thermal throttling): reduced likelihood for current tested device class due to QA-06 30.6-minute soak PASS with no crash/OOM/ANR signatures; residual OEM variability remains.
2. `R-005` (privacy claims divergence): mitigation evidence improved via diagnostics redaction/policy rejection regressions (QA-03 rerun PASS).
3. `R-006` (tool injection/execution abuse): mitigation evidence improved via WP-05 closeout contract stability and adversarial coverage.
4. `R-001` and `R-003` remain active medium/high launch-watch items until broader multi-device Stage-6 evidence expands beyond current executed hardware.

Go/no-go impact:

1. No new blocker risk introduced by current Stage-6 QA evidence set.
2. Residual high risks are tracked and require continued monitoring through WP-07 closure.
