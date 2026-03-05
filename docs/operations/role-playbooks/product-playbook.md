# Product Playbook

Last updated: 2026-03-05

## Mission

Keep MVP scope focused, maintain release-governance coherence, and run promote/hold decisions from complete evidence.

## Current Operating Truth

1. `WP-00` to `WP-12` are closed.
2. `WP-09` is in progress.
3. `WP-13` is open (`run-01` hold; moderated metrics missing).
4. Active policy: `internalDownload first` + `soft gate` pilot.

## Done

- [x] `PROD-01` launch workflow lock
- [x] `PROD-02` launch device policy lock
- [x] `PROD-03` stage acceptance signoff packet
- [x] `PROD-08` UX feedback taxonomy + intake policy
- [x] `PROD-09` soft-gate pilot policy (`docs/operations/prod-09-soft-gate-pilot-policy.md`)
- [x] `UX-12` recovery journey story spec (`docs/ux/ux-12-recovery-journey-spec.md`)

## In Progress

- [ ] `DOC-01` reconcile timeline/status drift across roadmap + operations docs
- [ ] `PROD-04` monetization hypothesis (parallel, non-blocking for MVP gate)
- [ ] `WP-13` usability gate closure prep and moderated run scheduling

## Ready Queue

- [ ] `QA-WP13-RUN02` moderated packet execution (with QA)
- [ ] `PROD-10` launch gate matrix decision run
- [ ] Promotion memo publish (`promote`/`iterate`/`hold`) after matrix review

## Task Queue

| Task ID | Task | Status | Owner | References |
|---|---|---|---|---|
| DOC-01 | Reconcile roadmap/board/playbook statuses and dates | In Progress | Product Ops | `docs/operations/execution-board.md`, `docs/roadmap/phase-1-mvp-plan.md`, `docs/roadmap/product-roadmap.md`, `docs/roadmap/team-workstreams.md` |
| PROD-09 | Publish soft-gate pilot policy | Done | Product Ops | `docs/operations/prod-09-soft-gate-pilot-policy.md` |
| UX-12 | Publish recovery journey acceptance spec | Done | Product + Design + Android | `docs/ux/ux-12-recovery-journey-spec.md` |
| QA-WP13-RUN02 | Execute moderated 5-user packet and thresholds | Ready | Product + QA | `docs/operations/wp-13-usability-gate-packet-template.md`, `docs/operations/evidence/wp-13/2026-03-04-wp13-usability-gate-run-01.md` |
| PROD-10 | Run promotion decision through unified launch gate matrix | Ready | Product Lead | `docs/operations/prod-10-launch-gate-matrix.md` |
| PROD-04 | Monetization scope and pricing hypothesis | In Progress | Product Lead | `docs/operations/prod-04-monetization-hypothesis-v0.md` |

## Product Decision Rule

1. Pilot expansion allowed only under `PROD-09` hard-stop rules.
2. Broader promotion requires completed moderated packet + all required matrix rows PASS.
3. Every publishable claim must be linked to evidence IDs in `PROD-10`.

## Product References

- `docs/operations/execution-board.md`
- `docs/operations/prod-09-soft-gate-pilot-policy.md`
- `docs/operations/prod-10-launch-gate-matrix.md`
- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/prd/phase-0-prd.md`
