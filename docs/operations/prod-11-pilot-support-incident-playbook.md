# PROD-11 Pilot Support and Incident UX-Ops Playbook

Last updated: 2026-03-05
Owner: Product Ops
Support: QA, Marketing, Engineering
Status: Ready

## Objective

Define pilot support expectations and incident handling so user trust and UX quality remain stable during soft-gate expansion.

## Support SLA (Pilot)

1. `S0` (data-loss/security/privacy breach): acknowledge <= 1 hour, mitigation owner assigned immediately.
2. `S1` (blocked core workflow A/B/C): acknowledge <= 4 hours, workaround/update <= 24 hours.
3. `S2` (degraded but usable): acknowledge <= 1 business day.

## Intake Channels

1. QA incident triage packet.
2. In-app diagnostics export attached to ticket.
3. Moderated usability notes for WP-13 runs.

## Incident Classification

1. UX/flow comprehension issue.
2. Runtime/performance issue.
3. Safety/privacy issue.
4. Distribution/onboarding setup issue.

## User-Facing Fallback Copy Rules

1. Provide explicit next action (`Retry`, `Refresh runtime checks`, `Fix model setup`).
2. Include deterministic error code where available.
3. Avoid speculative language; state known condition and recovery path.

## Escalation Path

1. QA triages and severity labels.
2. Product assigns owner + ETA.
3. Engineering/Security handles fix and evidence update.
4. Marketing pauses affected claims if parity risk exists.

## Acceptance

1. Weekly rollout summary includes incident counts by severity and closure ETA.
2. Promote decision requires no open `S0`/`S1` incidents.
