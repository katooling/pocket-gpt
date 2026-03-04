# MKT-01 Messaging Architecture (Final Lock, Evidence-Safe)

Last updated: 2026-03-04
Status: Finalized lock pass complete (post-WP-03)
Owner: Marketing Lead
Dependency baseline: WP-03 closure evidence

## Purpose

Define launch messaging across privacy, reliability, and utility with explicit claim labels:
1. `Validated` (publishable)
2. `Provisional` (not publishable until dependency closes)
3. `Excluded` (not publishable for MVP)

## Claim Governance

1. Every public claim must map to a documented source.
2. Publish only `Validated` claims.
3. Keep roadmap items clearly non-shipped unless evidence upgrades them.
4. Avoid comparative/superlative language without direct benchmark parity evidence.

## Pillar Claim Register

| Claim ID | Pillar | Claim summary | Label | Publishable | Dependency tag | Evidence source |
|---|---|---|---|---|---|---|
| P-01 | Privacy | Privacy-first/local-first by default is a vision anchor. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| P-02 | Privacy | Cloud-dependent default path is out of MVP scope. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/product/feature-catalog.md` |
| P-03 | Privacy | Privacy controls/policy gates are in MVP exit criteria. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| P-04 | Privacy | Policy/tool safety regressions are stage-gated. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/testing/test-strategy.md` |
| R-01 | Reliability | QA-02 Phase B ran on a physical Android device. | Validated | Yes | `DEP-QA02-PHASEB` | `docs/operations/evidence/wp-03/2026-03-03-qa-02-phase-b.md` |
| R-02 | Reliability | Scenario A/B threshold evaluation passed (closeout). | Validated | Yes | `DEP-QA02-CLOSEOUT` | `docs/operations/evidence/wp-03/2026-03-04-qa-02-closeout.md` |
| R-03 | Reliability | WP-03 is marked Done on execution board. | Validated | Yes | `DEP-WP03-BOARD-DONE` | `docs/operations/execution-board.md` |
| U-01 | Utility | H1 scope includes offline text chat and streaming output. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| U-02 | Utility | H1 scope includes 3-5 local deterministic tools and Memory v1. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| U-03 | Utility | Single-image understanding is included in MVP scope direction. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md`, `docs/product/feature-catalog.md` |
| U-04 | Utility | Claim publication must respect reliability-gate discipline. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/product/feature-catalog.md`, `docs/testing/test-strategy.md` |
| Q-01 | Reliability | Scenario C and memory quality outcomes are complete. | Provisional | No | `DEP-WP06-STAGE5` | `docs/testing/test-strategy.md`, `docs/operations/execution-board.md` |
| Q-02 | Reliability | Soak/go-no-go packet outcomes are complete. | Provisional | No | `DEP-WP07-STAGE6` | `docs/testing/test-strategy.md`, `docs/operations/execution-board.md` |
| X-01 | Utility | iOS parity is available now. | Excluded | No | `DEP-H2-NOT-SHIPPED` | `docs/roadmap/product-roadmap.md` |
| X-02 | Utility | Voice mode/STT/TTS is available now. | Excluded | No | `DEP-H3-NOT-SHIPPED` | `docs/roadmap/product-roadmap.md` |

## Finalized Messaging Pillars (Publishable)

1. Privacy: `Local-first by default, with privacy and policy controls treated as release-gated requirements.`
2. Reliability: `Validated real-device evidence demonstrates Scenario A/B threshold pass on the current artifact-validated runtime path.`
3. Utility: `Current MVP scope focuses on offline chat, streaming, local tools, memory v1, and single-image support.`

## Non-Publishable Queue

1. `Provisional`: Scenario C outcomes, soak outcomes.
2. `Excluded`: iOS-now claims, voice-now claims, universal performance guarantees.

## Lock Pass Outcome

1. Messaging baseline finalized against post-WP-03 evidence state.
2. Claim labels and dependency tags are explicit for governance and review.
3. MKT-02 must only consume `Validated` claim IDs for publishable rows.
