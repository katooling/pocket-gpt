# WP-09 QA Release Promotion Checklist

Date: 2026-03-04  
Owner: QA Lead  
Usage: Cohort expansion and release promotion gate

## Gate A: Build and Test Baseline

- [ ] Candidate build id + commit hash recorded.
- [ ] `bash scripts/dev/test.sh ci` latest result attached.
- [ ] Latest `android-instrumented` run id and status attached.
- [ ] Latest `maestro` run id and status attached.
- [ ] Release-candidate real-runtime app-path smoke run id attached (when promotion target includes runtime claims).
- [ ] No newly introduced `S0`/`S1` defects since prior promotion point.

## Gate B: Device and Reliability Signal

- [ ] Required-tier device coverage evidence attached.
- [ ] Crash/OOM/ANR signal trend acceptable for current cohort window.
- [ ] Any best-effort device caveats documented for release notes.

## Gate C: Safety/Policy Guardrails

- [ ] Latest routing/policy regression evidence remains valid.
- [ ] Latest tool-safety regression evidence remains valid.
- [ ] No unresolved privacy/security blocker risk.

## Gate D: Operational Readiness

- [ ] Incident triage loop active with named on-call owners.
- [ ] User feedback channel intake path active and monitored.
- [ ] Rollback/hold procedure documented for this promotion step.

## Gate E: WP-13 UX Promotion Readiness

- [ ] Onboarding completion signal for current cohort window attached.
- [ ] Runtime/model status confusion signal reviewed and within threshold.
- [ ] Privacy comprehension signal reviewed and within threshold.
- [ ] Natural-language tool path PASS evidence attached from latest QA matrix run.
- [ ] Scenario C context follow-up Maestro evidence attached (`tests/maestro/scenario-c.yaml`).

## Promotion Decision

1. QA recommendation (`promote`/`hold`):
2. Reason summary:
3. Conditions (if hold or conditional promote):
4. Product concurrence (`yes`/`no`):
5. Engineering concurrence (`yes`/`no`):
6. Decision date (UTC):

## Evidence Links

1. QA checkpoint packet: `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-rollout-quality-checkpoints.md`
2. Incident triage template: `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-incident-triage-template.md`
3. Weekly QA summary template: `docs/operations/evidence/wp-09/2026-03-04-qa-wp09-weekly-rollout-summary-template.md`
4. Example raw artifact root: `scripts/benchmarks/runs/2026-03-04/DEVICE_SERIAL_REDACTED/qa-06-soak-20260304-095133/`
