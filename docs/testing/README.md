# Testing Docs Index

Canonical ownership by concern:

1. Engineering workflow and command contract: `scripts/dev/README.md`
2. Canonical test strategy and release gates: `docs/testing/test-strategy.md`
3. Task-focused execution runbooks: `docs/testing/runbooks.md`
4. Screenshot capture/manual regression workflow: `docs/testing/screenshot-regression-workflow.md`
5. Weekly QA UI regression matrix (`UI-01..UI-15`): `docs/testing/wp-09-ui-regression-matrix.md`
6. Maestro flow assets and lifecycle flow contract: `tests/maestro/`
7. CI required lifecycle gate job: `lifecycle-e2e-first-run` in `.github/workflows/ci.yml`

Consolidation rule:

- Avoid duplicate command/process docs outside this index, `scripts/dev/README.md`, and the canonical strategy/runbook docs.
