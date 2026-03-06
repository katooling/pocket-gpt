# MKT-02 Competitor Matrix (Evidence-Safe, Post-WP-03)

Last updated: 2026-03-04
Lifecycle: Finalized for Pocket GPT claim set; competitor snapshot sourced (ChatGPT/Gemini/Claude)
Owner: Product Marketing
Prerequisites: MKT-01 baseline complete, WP-03 marked Done

## Scope and Rules

1. Pocket GPT statements are limited to claims mapped in `docs/operations/mkt-01-messaging-architecture-draft.md`.
2. Each claim is labeled: `Validated`, `Provisional`, or `Excluded`.
3. Only `Validated` claims are publishable.
4. Competitor cells require external citation; otherwise must be `Unknown`.

## External Source Register (2026-03-04 Snapshot)

Competitor columns in this file are fixed as:
1. Competitor A = ChatGPT (OpenAI)
2. Competitor B = Gemini Apps (Google)
3. Competitor C = Claude (Anthropic)

Primary external sources:
1. OpenAI Data Controls FAQ: [help.openai.com/en/articles/7730893-data-controls-faq](https://help.openai.com/en/articles/7730893-data-controls-faq)
2. OpenAI Voice Mode FAQ: [help.openai.com/en/articles/8400625-voice-mode-faq](https://help.openai.com/en/articles/8400625-voice-mode-faq)
3. Google Gemini privacy hub: [support.google.com/gemini/answer/13594961](https://support.google.com/gemini/answer/13594961)
4. Google Gemini apps availability: [support.google.com/gemini/answer/16103362](https://support.google.com/gemini/answer/16103362)
5. Anthropic training usage policy: [support.anthropic.com/en/articles/7996868-is-my-data-used-for-model-training](https://support.anthropic.com/en/articles/7996868-is-my-data-used-for-model-training)
6. Anthropic voice mode mobile apps: [support.anthropic.com/en/articles/12304248-how-to-use-voice-mode-with-claude-mobile-apps](https://support.anthropic.com/en/articles/12304248-how-to-use-voice-mode-with-claude-mobile-apps)

## Claim Risk Register

| Claim ID | Claim summary | Label | Publishable | Dependency tag | Evidence source |
|---|---|---|---|---|---|
| P-01 | Privacy-first/local-first by default (vision anchor). | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| P-02 | Cloud-dependent default path is out of MVP scope. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/product/feature-catalog.md` |
| P-03 | Privacy/policy controls are MVP exit criteria. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| P-04 | Policy/tool safety regressions are stage-gated. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/testing/test-strategy.md` |
| R-01 | QA-02 Phase B executed on physical device. | Validated | Yes | `DEP-QA02-PHASEB` | `docs/operations/evidence/index.md` |
| R-02 | Scenario A/B threshold evaluation passed. | Validated | Yes | `DEP-QA02-CLOSEOUT` | `docs/operations/evidence/index.md` |
| R-03 | WP-03 status is Done. | Validated | Yes | `DEP-WP03-BOARD-DONE` | `docs/operations/execution-board.md` |
| R-04 | Artifact/evidence reproducibility policy is defined. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/testing/test-strategy.md` |
| U-01 | H1 includes offline text chat + streaming output. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| U-02 | H1 includes 3-5 local deterministic tools + Memory v1. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md` |
| U-03 | Single-image understanding is in MVP scope direction. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/roadmap/product-roadmap.md`, `docs/product/feature-catalog.md` |
| U-04 | Feature claims must respect reliability gate discipline. | Validated | Yes | `DEP-WP03-CLOSED` | `docs/product/feature-catalog.md`, `docs/testing/test-strategy.md` |
| X-01 | Scenario C/memory quality pass complete. | Validated | Yes | `DEP-WP06-CLOSED` | `docs/operations/evidence/index.md`, `docs/operations/execution-board.md` |
| X-02 | Soak/go-no-go packet complete. | Validated | Yes | `DEP-WP07-CLOSED` | `docs/operations/evidence/index.md`, `docs/operations/execution-board.md` |
| X-03 | iOS parity available now. | Excluded | No | `DEP-H2-NOT-SHIPPED` | `docs/roadmap/product-roadmap.md` |
| X-04 | Voice mode/STT/TTS available now. | Excluded | No | `DEP-H3-NOT-SHIPPED` | `docs/roadmap/product-roadmap.md` |
| X-05 | Universal device performance guarantee. | Excluded | No | `DEP-NO-EVIDENCE` | WP-03 evidence limited to tested runs/devices |

## Competitor Matrix (Publishable Rows Only)

Note: `Unknown` is preserved where no source-backed statement was found.  
Note: Cells explicitly marked `Inference` are reasoned from cited data-handling statements, not direct vendor wording.

| Capability | Pocket GPT (publishable text) | ChatGPT (OpenAI) | Gemini Apps (Google) | Claude (Anthropic) | Claim label | Dependency tag |
|---|---|---|---|---|---|---|
| Local-first default posture | Privacy-first, local-first by default in product vision. | No local-first-default claim found in sourced docs (`Unknown` for local-first parity). | No local-first-default claim found in sourced docs (`Unknown` for local-first parity). | No local-first-default claim found in sourced docs (`Unknown` for local-first parity). | Validated | `DEP-WP03-CLOSED` |
| Cloud dependency default path | Cloud-dependent default path is out of current MVP scope. | `Inference`: cloud-processed by default based on account/data-control and training controls docs. | `Inference`: cloud-processed by default; Gemini Apps data may be reviewed and retained per privacy hub controls. | `Inference`: Free/Pro conversations may be used for model improvement by default; opt-out available. | Validated | `DEP-WP03-CLOSED` |
| Policy/privacy gate discipline | Privacy and policy controls are explicitly release-gated. | Training can be disabled; Temporary Chats not used to train. | Gemini Apps activity and deletion controls are documented; some data may be reviewed by humans. | Training policy differs by tier (Free/Pro opt-out; Team/Enterprise/API default no training). | Validated | `DEP-WP03-CLOSED` |
| Real-device reliability evidence | Real-device Scenario A/B evidence exists with threshold PASS. | Unknown (no public Scenario-style threshold packet in sourced docs). | Unknown (no public Scenario-style threshold packet in sourced docs). | Unknown (no public Scenario-style threshold packet in sourced docs). | Validated | `DEP-QA02-CLOSEOUT` |
| Reproducible artifact policy | Artifact + evidence linkage policy is defined for stage closure. | Unknown (no artifact-path policy equivalent found in sourced docs). | Unknown (no artifact-path policy equivalent found in sourced docs). | Unknown (no artifact-path policy equivalent found in sourced docs). | Validated | `DEP-WP03-CLOSED` |
| MVP utility scope | H1/MVP scope includes offline chat, streaming, local tools, memory, and single-image path. | Mobile app controls exist (iOS/Android settings) and voice mode is available on mobile apps. | Gemini Apps support includes Android, iOS, and web availability. | Voice mode is supported on Claude mobile apps (iOS/Android). | Validated | `DEP-WP03-CLOSED` |

## Non-Publishable Claims Queue

| Claim | Label | Blocker |
|---|---|---|
| iOS parity available now | Excluded | H2 target, not current shipped scope |
| Voice conversation mode available now | Excluded | H3 target, not current shipped scope |
| Universal device-class performance statement | Excluded | No broad evidence basis |

## Evidence Alignment Check (Post-WP-03 Reality)

1. WP-03 is marked `Done` on execution board (2026-03-04 board state).
2. QA-02 closeout rerun evidence exists (`2026-03-04-qa-02-closeout.md`).
3. Matrix publishability is restricted to `Validated` claims only.
4. Provisional and excluded claims are separated with explicit dependency tags.
