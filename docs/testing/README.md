# Testing Docs Index

Canonical ownership by concern:

1. Engineering workflow and command contract: `scripts/dev/README.md`
2. Test strategy, release gates, CI-vs-human boundary: `docs/testing/test-strategy.md`
3. Android device lane execution details: `docs/testing/android-dx-and-test-playbook.md`
4. Lane profiles and changed-file selection matrix: `docs/testing/test-lane-profiles-and-selection.md`
5. Screenshot capture and manual regression workflow: `docs/testing/screenshot-regression-workflow.md`
6. Weekly QA UI regression matrix (`UI-01..UI-15`): `docs/testing/wp-09-ui-regression-matrix.md`
7. Maestro flow assets: `tests/maestro/`

Consolidation note:

- Redundant companion docs for CLI validation and Stage-2 runbook were removed on 2026-03-08 to reduce command duplication and drift.
- Use the canonical docs above for all current command/process guidance.
