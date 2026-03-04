# MKT-02 Competitor Matrix (Evidence-Safe, Post-WP-03)

Last updated: 2026-03-04
Status: Finalized for Pocket GPT claim set; competitor cells remain source-gated
Owner: Product Marketing
Prerequisites: MKT-01 baseline complete, WP-03 marked Done

## Scope and Rules

1. Pocket GPT statements are limited to claims mapped in `docs/operations/mkt-01-messaging-architecture-draft.md`.
2. Each claim is labeled: `Validated`, `Provisional`, or `Excluded`.
3. Only `Validated` claims are publishable.
4. Competitor cells require external citation; otherwise must be `Unknown`.

## Claim Risk Register

| Claim ID | Claim summary | Label | Publishable | Dependency tag | Evidence source |
|---|---|---|---|---|---|
| P-01 | Privacy-first/local-first by default (vision anchor). | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| P-02 | Cloud-dependent default path is out of MVP scope. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/product/feature-catalog.md` |
| P-03 | Privacy/policy controls are MVP exit criteria. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| P-04 | Policy/tool safety regressions are stage-gated. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/testing/test-strategy.md` |
| R-01 | QA-02 Phase B executed on physical device. | Validated | Yes | `DEP-QA02-PHASEB` | `docs/operations/evidence/wp-03/2026-03-03-qa-02-phase-b.md` |
| R-02 | Scenario A/B threshold evaluation passed. | Validated | Yes | `DEP-QA02-CLOSEOUT` | `docs/operations/evidence/wp-03/2026-03-04-qa-02-closeout.md` |
| R-03 | WP-03 status is Done. | Validated | Yes | `DEP-WP03-BOARD-DONE` | `docs/operations/execution-board.md` |
| R-04 | Artifact/evidence reproducibility policy is defined. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/testing/test-strategy.md` |
| U-01 | H1 includes offline text chat + streaming output. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| U-02 | H1 includes 3-5 local deterministic tools + Memory v1. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| U-03 | Single-image understanding is in MVP scope direction. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md`, `docs/product/feature-catalog.md` |
| U-04 | Feature claims must respect reliability gate discipline. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/product/feature-catalog.md`, `docs/testing/test-strategy.md` |
| X-01 | Scenario C/memory quality pass complete. | Provisional | No | `DEP-WP06-STAGE5` | `docs/roadmap/product-roadmap.md`, `docs/testing/test-strategy.md` |
| X-02 | Soak/go-no-go packet complete. | Provisional | No | `DEP-WP07-STAGE6` | `docs/testing/test-strategy.md`, `docs/operations/execution-board.md` |
| X-03 | iOS parity available now. | Excluded | No | `DEP-H2-NOT-SHIPPED` | `docs/roadmap/product-roadmap.md` |
| X-04 | Voice mode/STT/TTS available now. | Excluded | No | `DEP-H3-NOT-SHIPPED` | `docs/roadmap/product-roadmap.md` |
| X-05 | Universal device performance guarantee. | Excluded | No | `DEP-NO-EVIDENCE` | WP-03 evidence limited to tested runs/devices |

## Competitor Matrix (Publishable Rows Only)

Note: Competitor data is intentionally unfilled until sourced. Use `Unknown` where evidence is not yet cited.

| Capability | Pocket GPT (publishable text) | Competitor A | Competitor B | Competitor C | Claim label | Dependency tag |
|---|---|---|---|---|---|---|
| Local-first default posture | Privacy-first, local-first by default in product vision. | Unknown | Unknown | Unknown | Validated | `DEP-WP03-CLOSED` |
| Cloud dependency default path | Cloud-dependent default path is out of current MVP scope. | Unknown | Unknown | Unknown | Validated | `DEP-WP03-CLOSED` |
| Policy/privacy gate discipline | Privacy and policy controls are explicitly release-gated. | Unknown | Unknown | Unknown | Validated | `DEP-WP03-CLOSED` |
| Real-device reliability evidence | Real-device Scenario A/B evidence exists with threshold PASS. | Unknown | Unknown | Unknown | Validated | `DEP-QA02-CLOSEOUT` |
| Reproducible artifact policy | Artifact + evidence linkage policy is defined for stage closure. | Unknown | Unknown | Unknown | Validated | `DEP-WP03-CLOSED` |
| MVP utility scope | H1/MVP scope includes offline chat, streaming, local tools, memory, and single-image path. | Unknown | Unknown | Unknown | Validated | `DEP-WP03-CLOSED` |

## Non-Publishable Claims Queue

| Claim | Label | Blocker |
|---|---|---|
| Scenario C and memory quality outcomes | Provisional | Stage 5/WP-06 evidence not yet complete |
| Soak reliability and beta go/no-go outcomes | Provisional | Stage 6/WP-07 evidence not yet complete |
| iOS parity available now | Excluded | H2 target, not current shipped scope |
| Voice conversation mode available now | Excluded | H3 target, not current shipped scope |
| Universal device-class performance statement | Excluded | No broad evidence basis |

## Evidence Alignment Check (Post-WP-03 Reality)

1. WP-03 is marked `Done` on execution board (2026-03-04 board state).
2. QA-02 closeout rerun evidence exists (`2026-03-04-qa-02-closeout.md`).
3. Matrix publishability is restricted to `Validated` claims only.
4. Provisional and excluded claims are separated with explicit dependency tags.
