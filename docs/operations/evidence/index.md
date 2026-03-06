# Evidence Index

Last updated: 2026-03-06

This index is the canonical evidence inventory after hard-prune.

## Retention Policy

1. Full evidence retained for active packages: `WP-09`, `WP-13`.
2. Production-claim-critical evidence retained for `WP-12` only.
3. Historical packages are summarized here and their per-run notes are pruned from tree.
4. Full history remains recoverable via git.

## Active Full Evidence Sets

### WP-09 (`docs/operations/evidence/wp-09/`)

- Full active set retained for distribution, QA rollout, UX feedback, and marketing operations evidence.

### WP-13 (`docs/operations/evidence/wp-13/`)

- Full active set retained for UX-quality closure, usability packet support, and runtime/user-journey closure evidence.

## WP-12 Retained (Production-Claim Critical)

Directory: `docs/operations/evidence/wp-12/`

- `2026-03-04-eng-12-model-distribution-implementation.md`
- `2026-03-04-eng-13-native-runtime-proof.md`
- `2026-03-04-eng-17-network-policy-wiring.md`
- `2026-03-04-prod-eng-12-model-distribution-decision.md`
- `2026-03-04-qa-wp12-closeout.md`
- `2026-03-05-eng-13-native-runtime-rerun.md`
- `2026-03-05-qa-wp12-closeout-rerun.md`

## Historical Summaries (Pruned)

- `WP-01`: CI/bootstrap baseline completed; closure evidence archived by prune policy.
- `WP-02`: first real Android runtime/device pass completed; detailed run notes pruned.
- `WP-03`: artifact+benchmark reliability closure completed; detailed notes pruned.
- `WP-04`: routing/policy/diagnostics hardening completed; detailed notes pruned.
- `WP-05`: tool runtime safety closure completed; detailed notes pruned.
- `WP-06`: memory+image productionization closure completed; detailed notes pruned.
- `WP-07`: soak/go-no-go closure completed; detailed notes pruned.
- `WP-08`: positioning/asset lock pass completed; detailed notes pruned.
- `WP-11`: Android MVP UI gate closure completed; detailed notes pruned.
