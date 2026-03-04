# Feature Catalog and Feasibility Bands

Last updated: 2026-03-04

This catalog lists current and possible features based on known technical constraints (mobile RAM/thermal limits, model/runtime maturity, and privacy requirements).

## Band A: MVP-Critical (Build Now)

| Feature | Why It Matters | Constraints | Status |
|---|---|---|---|
| Offline text chat | core assistant utility | first-token latency, memory/OOM | In active implementation |
| Streaming responses | perceived speed and UX quality | runtime callback reliability | In active implementation |
| Model routing (`0.8B`/`2B`) | battery and thermal control | device-state signal quality | In active implementation |
| Local tool runtime (3-5 tools) | practical daily utility | strict validation/sandboxing | Implemented for current MVP scope (WP-05 closed 2026-03-04) |
| Memory v1 | continuity across sessions | retrieval quality + retention policy | In active implementation |
| Single-image Q&A | multimodal differentiation | image path latency and correctness | In active implementation |

## Band B: Near-Term Expansion (Post-MVP)

| Feature | Why It Matters | Constraints | Status |
|---|---|---|---|
| iOS parity | market expansion | runtime bridge and platform integration | Planned |
| SQLite-backed memory | durable reliability | migrations and pruning correctness | Planned |
| Strict JSON-schema tools | stronger safety guarantees | parser contract and test coverage | Implemented (ENG-06 closeout 2026-03-04) |
| Rich diagnostics dashboards | faster QA and regression triage | safe redaction + metrics consistency | Planned |
| Better image workflows | broader use cases (documents/photos) | model quality on edge cases | Planned |

## Band C: Voice Layer (Medium-Term)

| Feature | Why It Matters | Constraints | Status |
|---|---|---|---|
| Offline STT | natural input modality | model size/latency on-device | Planned |
| Offline/Hybrid TTS | hands-free output and accessibility | voice quality vs power usage | Planned |
| Voice conversation mode | stronger assistant UX | interruption handling + latency budgets | Planned |
| Wake/quick actions | fast invocation | OS policy restrictions and battery impact | Research |

## Band D: Advanced/Long-Term

| Feature | Why It Matters | Constraints | Status |
|---|---|---|---|
| Bounded multi-step workflows | higher task completion | safety and predictability | Research |
| Optional encrypted sync | multi-device continuity | explicit consent and privacy boundaries | Research |
| Pro model tiers (`4B`/`9B`) | quality for capable devices | thermals + sustained UX | Research |
| Domain packs/adapters | personalization and specialization | quality assurance + policy controls | Research |

## Out of Scope for Current MVP

1. Broad video analytics workflows
2. On-device training/fine-tuning
3. Unbounded autonomous agent loops
4. Cloud-dependent default path

## Feature Prioritization Rules

1. Must improve daily utility for ICP users.
2. Must preserve local-first privacy guarantees.
3. Must pass benchmark and reliability gates.
4. Must not destabilize current stage commitments.
