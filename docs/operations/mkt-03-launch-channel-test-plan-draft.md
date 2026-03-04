# MKT-03 Launch Channel Test Plan (Draft, Evidence-Safe)

Last updated: 2026-03-04
Status: Draft complete; execution unblocked by WP-07/WP-11 closure
Owner: Growth Lead / Product Marketing

## Objective

Define measurable launch-channel experiments for communities and beta cohorts while keeping all public-facing claims restricted to `Validated` messaging from MKT-01/MKT-02.

## Claim Policy For Channel Tests

1. Public-facing channel copy may use only `Validated` claims from:
   - `docs/operations/mkt-01-messaging-architecture-draft.md`
   - `docs/operations/mkt-02-competitor-matrix-template-draft.md`
2. `Provisional` claims are internal-only and must be tagged with dependencies.
3. `Excluded` claims are never used in channel tests.

## Allowed Claim Set (Channel Copy)

| Channel Claim ID | Source Claim IDs | Label | Allowed in public tests |
|---|---|---|---|
| CH-01 | P-01, P-02 | Validated | Yes |
| CH-02 | P-03, P-04 | Validated | Yes |
| CH-03 | R-01, R-02, R-03 | Validated | Yes |
| CH-04 | U-01, U-02, U-03, U-04 | Validated | Yes |
| CH-05 | Q-01, Q-02 | Validated | Yes |

## Test Windows

1. Phase A (Prep, now): build assets, targeting rules, intake process, and tracking.
2. Phase B (Controlled release, now): run channel tests with publishable claims only.
3. Phase C (Scale decision, after first 7 days of data): keep/iterate/stop by channel.

## Channel Experiments

### Experiment 1: Privacy/Offline Communities

Hypothesis:
`Evidence-gated privacy + offline reliability positioning will produce higher qualified waitlist conversion than generic AI utility framing.`

Channel examples:
1. privacy-focused communities
2. offline/edge AI communities

Copy scope:
- Use `CH-01`, `CH-02`, `CH-03`
- Do not include performance superlatives or unsourced competitor statements

Success metrics:
1. CTR to waitlist page
2. Waitlist conversion rate
3. Qualified lead ratio (device + use-case fit)

Stop criteria:
1. Conversion below baseline for 2 consecutive test windows
2. High mismatch feedback on scope/expectation

### Experiment 2: Android Power-User Cohorts

Hypothesis:
`MVP utility clarity (offline chat + streaming + local tools + memory + single-image) increases qualified beta applications.`

Audience:
1. Android enthusiasts
2. productivity/power-user testers

Copy scope:
- Use `CH-04` with `CH-03`
- Include support caveat aligned to launch device policy

Success metrics:
1. Application completion rate
2. Qualified cohort acceptance rate
3. First-week activation rate

Stop criteria:
1. Low activation despite high signup volume
2. Repeated confusion about unsupported platforms/features

### Experiment 3: Beta Partner Outreach (Small Batch)

Hypothesis:
`Structured outreach to small partner cohorts improves high-quality feedback density vs broad open calls.`

Audience:
1. small founder/operator communities
2. trusted tester groups with reproducible bug reporting behavior

Copy scope:
- Use `CH-01` through `CH-04`
- Include explicit “MVP scope + evidence-gated roadmap” statement

Success metrics:
1. Response rate
2. Accepted beta invites
3. Actionable feedback per active tester

Stop criteria:
1. Low signal-to-noise feedback
2. High support burden vs cohort value

## Evidence-Safe Messaging Blocks

### Block A (Privacy/Local-First)

`Private by default and local-first by design. Cloud-dependent default behavior is out of MVP scope.`

Mapping: `CH-01`

### Block B (Reliability)

`Reliability claims are tied to real-device Scenario A/B threshold evidence on the artifact-validated runtime path.`

Mapping: `CH-03`

### Block C (Utility)

`Current MVP utility focuses on offline chat, streaming output, local tools, memory v1, and single-image support.`

Mapping: `CH-04`

## Provisional/Internal-Only Blocks (Do Not Publish)

1. No currently active provisional block; keep this section for future staged claims.

## Execution Checklist

1. Confirm WP-07/WP-11 remain `Done` before external publication.
2. Run copy QA against claim map (`CH-*`) before each post/send.
3. Require source link attachment for every external-facing claim line.
4. Record experiment outcomes and decision notes in WP-09 channel planning artifacts.

## Outputs For Handoff

1. Channel brief for each experiment (audience, copy, metric targets).
2. Approved message bank with claim IDs.
3. 7-day scorecard template (CTR, conversion, qualification, activation, feedback quality).
