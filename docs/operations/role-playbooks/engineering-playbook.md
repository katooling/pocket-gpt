# Engineering Playbook

Last updated: 2026-03-06

## Mission

Ship stable runtime, UX infrastructure, and lane behavior while preserving privacy and safety contracts.

## Responsibilities

1. Own runtime/app contract correctness and deterministic failure behavior.
2. Maintain lane reliability for `android-instrumented`, `maestro`, `journey`, and Stage-2 profiles.
3. Keep code, tests, and docs aligned for user-visible behavior changes.
4. Publish engineering evidence notes for release-impacting work.

## Working Rules

1. Every behavior change must have the right-layer test coverage.
2. Release-impacting changes require evidence notes under `docs/operations/evidence/`.
3. Avoid duplicating status in playbooks; update `docs/operations/execution-board.md` instead.
4. Ticket design/acceptance details live in `docs/operations/tickets/`.

## Primary References

- `docs/operations/execution-board.md`
- `docs/operations/tickets/eng-20-runtime-cancel-timeout-contract.md`
- `docs/testing/test-strategy.md`
- `docs/testing/android-dx-and-test-playbook.md`
- `docs/testing/test-lane-profiles-and-selection.md`
- `scripts/dev/README.md`
