# Maestro Flows

Maestro E2E flow assets for Android UI/journey validation.

Source of truth for execution commands and cloud guidance:

- `scripts/dev/README.md`
- `docs/testing/test-strategy.md`
- `docs/testing/runbooks.md`

## Flow Set

1. `scenario-a.yaml`: send-message chat loop smoke
2. `scenario-b.yaml`: advanced controls/tools/diagnostics journey
3. `scenario-c.yaml`: continuity + image-aware journey
4. `scenario-activation-send-smoke.yaml`: activation + send recovery smoke
5. `scenario-first-run-download-chat.yaml`: clean install -> first-run download -> runtime ready -> send smoke

## Contract Notes

1. Flows run with `clearState: true` to avoid cross-scenario leakage.
2. Screenshot checkpoints feed `tests/ui-screenshots/inventory.yaml` through `lane screenshot-pack`.
3. Release-gate usage remains through `devctl` lanes; direct `maestro`/cloud execution is supplemental.
4. CI lifecycle gate flow is `scenario-first-run-download-chat.yaml` (`lifecycle-e2e-first-run` job in CI).
