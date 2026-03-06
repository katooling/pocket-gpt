# MKT-04 Landing Page + Launch Copy v1 (Execution Draft, Evidence-Safe)

Last updated: 2026-03-04
Lifecycle: Draft in progress (publish-ready lane)
Publish gate: WP-07/WP-11 are closed; publication requires asset capture + copy QA signoff
Owner: Content Lead / Product Marketing
Claim sources: `docs/operations/mkt-01-messaging-architecture-draft.md`, `docs/operations/mkt-02-competitor-matrix-template-draft.md`

## Copy Governance

1. Use only `Validated` claims for any publishable copy candidate.
2. Keep `Provisional` claims in clearly separated internal-only sections.
3. Exclude `Excluded` claims entirely from launch copy.
4. No comparative/superlative language without direct parity evidence.

## Claim Map for This Draft

| Copy Claim ID | Source Claim IDs | Label | Publishable now | Dependency |
|---|---|---|---|---|
| LP-01 | P-01, P-02 | Validated | Yes | `DEP-WP03-CLOSED` |
| LP-02 | P-03, P-04 | Validated | Yes | `DEP-WP03-CLOSED` |
| LP-03 | R-01, R-02, R-03 | Validated | Yes | `DEP-WP03-CLOSED` |
| LP-04 | U-01, U-02, U-03, U-04 | Validated | Yes | `DEP-WP03-CLOSED` |
| LP-05 | Q-01, Q-02 | Validated | Yes | `DEP-WP06-WP07-CLOSED` |

## Landing Page Draft (Validated-Only Candidate Copy)

### Hero

Headline:
`Private by default. Reliable on real devices. Useful every day offline.`

Subheadline:
`Pocket GPT is built local-first with release-gated privacy and policy controls, validated real-device reliability evidence, and practical MVP utility.`

Primary CTA:
`Join the Beta Waitlist`

Secondary CTA:
`Read Evidence Notes`

### Proof Strip

1. `Local-first by default; cloud-dependent default path is out of MVP scope.` (`LP-01`)
2. `Scenario A/B/C and soak/go-no-go evidence are documented on physical Android test runs.` (`LP-03`, `LP-05`)
3. `Offline chat, streaming, local tools, memory v1, and single-image support are in current MVP scope.` (`LP-04`)

### Section: Privacy

Heading:
`Local-first by design`

Body:
`Privacy-first/local-first is a product anchor. Privacy and policy controls are release-gated requirements, and claim publication is restricted to validated evidence.`

Claim refs: `LP-01`, `LP-02`

### Section: Reliability

Heading:
`Evidence-backed reliability`

Body:
`Current reliability messaging is grounded in real-device QA execution with Scenario A/B/C threshold evidence and Stage-6 soak/go-no-go closeout on the artifact-validated runtime path.`

Claim refs: `LP-03`

### Section: Utility

Heading:
`Useful MVP scope, clearly defined`

Body:
`Current MVP utility centers on offline text chat with streaming output, local deterministic tools, memory v1, and single-image understanding.`

Claim refs: `LP-04`

### Section: Transparency Note

Body:
`This page is evidence-gated. New claims are added only when linked package evidence is complete and reviewed.`

## Launch Copy v1 Snippets (Validated-Only Candidate Copy)

1. `Pocket GPT is local-first by default and designed around evidence-gated release discipline.` (`LP-01`, `LP-02`)
2. `Reliability claims are tied to documented real-device Scenario A/B threshold pass evidence.` (`LP-03`)
3. `MVP scope messaging is intentionally constrained to currently validated capabilities.` (`LP-04`)

## Internal-Only Provisional Copy (Do Not Publish)

1. No currently active provisional copy block; keep this section for future staged claims only.

## Explicit Exclusions (Do Not Use)

1. `iOS parity is available now.` (`Excluded`)
2. `Voice mode/STT/TTS is available now.` (`Excluded`)
3. `Universal device performance guarantee.` (`Excluded`)

## Publish Readiness Checklist

1. Confirm WP-07 and WP-11 remain `Done` on `docs/operations/execution-board.md`.
2. Remove or retain only validated sections for public channels.
3. Verify every public line still maps to `Validated` claim IDs.
4. Re-check excluded sections are not present in public copy.

## Demo Asset Capture Plan

1. Capture real screenshots/video via:
   - `bash scripts/marketing/capture_mobile_demo_assets.sh --output docs/operations/assets/mkt-04/YYYY-MM-DD --serial <device>`
2. Follow storyboard and claim QA checklist in:
   - `docs/operations/mkt-04-demo-asset-capture-runbook.md`
3. Block publication of any asset/caption pair that contains provisional or excluded claims.
