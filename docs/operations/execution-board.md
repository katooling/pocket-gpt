# Execution Board

Last updated: 2026-03-09

This is the single mutable board for planning and delivery.

## Program Standard

Engineering and quality excellence are mandatory.

1. Task completion requires acceptance criteria, test proof, and evidence links.
2. Regressions are blockers and must be resolved before scope expansion.
3. Promotion decisions require technical correctness and UX quality confidence.

## Status Legend

- `Done`: completed with evidence and acceptance criteria met
- `In Progress`: currently being executed
- `Ready`: unblocked and queued
- `Blocked`: cannot proceed (blocker listed)
- `Backlog`: not started and not yet ready

## Source Links

- Current release plan: `docs/roadmap/current-release-plan.md`
- Active ticket specs: `docs/operations/tickets/`
- Evidence inventory + retention: `docs/operations/evidence/index.md`
- Command/process canon: `scripts/dev/README.md`, `docs/testing/test-strategy.md`, `docs/testing/runbooks.md`

## Work Packages

| ID | Work Package | Status | Notes |
|---|---|---|---|
| WP-00 .. WP-08 | Foundation through launch prep packages | Done | Historical evidence summarized in `docs/operations/evidence/index.md` |
| WP-09 | Distribution plan and beta operations | In Progress | Active full evidence retained in `evidence/wp-09/` |
| WP-10 | Voice horizon discovery | Backlog | Post-MVP horizon |
| WP-11 | Android MVP UX package | Done | Historical evidence summarized in `docs/operations/evidence/index.md` |
| WP-12 | Backend production runtime closure | Done | Production-claim-critical evidence retained in `evidence/wp-12/` |
| WP-13 | UX quality closure | In Progress | Active full evidence retained in `evidence/wp-13/` |

## Current Sprint Board

### In Progress

- [ ] DOC-01 timeline/status reconciliation across roadmap + board + role docs
- [ ] DOC-02 product/UX doc parity sync for timeout/cancel/send-capture + manifest outage UX
- [ ] ENG-20 runtime cancel/timeout contract hardening
- [ ] WP-09 distribution plan and beta operations execution
- [ ] WP-13 run-01 hold closure prep

### Blocked

- [ ] QA-11 rerun `android-instrumented` + `maestro` + strict `journey` (`--mode strict --repeats 3`) blocked by runtime-state failures (`MainActivityUiSmokeTest`; `scenario-b` startup degradation `UI-STARTUP-001` / `MODEL_ARTIFACT_CONFIG_MISSING`). Targeted strict subset (`send-capture` + `scenario-a`) now passes on A51.
- [ ] QA-WP13-RUN02 moderated 5-user usability run packet completion blocked pending required-lane reliability rerun
- [ ] PROD-10 launch gate matrix decision run blocked by required-row failures (`S-D`, `S-E`, `S-F`, `S-G`)

### Ready

- [ ] UX-13 stuck send + timeout recovery UX acceptance coverage
- [ ] QA-13 send-capture gate operationalization in weekly regression workflow
- [ ] SEC-02 privacy claim parity audit
- [ ] PROD-11 pilot support + incident UX-ops playbook
- [ ] MKT-08 proof asset capture + listing shotlist finalization
- [ ] MKT-09 first 7-day channel scorecard execution
- [ ] MKT-10 claim freeze v1

### Done (Recent)

- [x] PROD-09 soft-gate pilot policy published
- [x] UX-12 recovery journey spec published
- [x] ENG-21 interaction architecture refactor landed
- [x] ENG-19 devctl package UID parser hardening landed (`userId`/`appId`/`uid` + tests)
- [x] ENG-22 provisioning startup-check lane blocker closed (`docs/operations/tickets/eng-22-provisioning-startup-check-lane-blocker.md`)
- [x] WP-12 package closeout complete

## Owner Focus (Current)

1. Engineering: close ENG-20 and stabilize send/runtime flow reliability for required-lane reruns.
2. QA: rerun required lanes after ENG-22 closure, then execute moderated WP-13 run-02 packet.
3. Product: close DOC-01 and DOC-02, then run PROD-10 decision flow.
4. Marketing: execute MKT-08 and MKT-09, then finalize MKT-10 claim freeze.

## Evidence Requirements (Current)

1. Active package evidence belongs in `docs/operations/evidence/wp-09/` and `docs/operations/evidence/wp-13/`.
2. Production-claim-critical WP-12 evidence remains in `docs/operations/evidence/wp-12/`.
3. Historical package detail is summarized in `docs/operations/evidence/index.md`.
