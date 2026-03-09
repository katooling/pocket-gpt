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
6. `scenario-first-run-gpu-chat.yaml`: clean install -> first-run setup -> enable GPU acceleration -> send smoke

## Contract Notes

1. Most flows run with `clearState: true` to avoid cross-scenario leakage; `scenario-b` keeps state so provisioned runtime metadata is preserved in journey/maestro lane sequencing.
2. Screenshot checkpoints feed `tests/ui-screenshots/inventory.yaml` through `lane screenshot-pack`.
3. Release-gate usage remains through `devctl` lanes; direct `maestro`/cloud execution is supplemental.
4. CI lifecycle gate flow is `scenario-first-run-download-chat.yaml` (`lifecycle-e2e-first-run` job in CI).

## Scoped Debug Flows

1. For short-lived bug investigations, create minimal one-path flows in `tmp/` and pair them with explicit logcat capture (see `docs/testing/runbooks.md` "Scoped Device Crash Repro").
2. Keep `tests/maestro/` for stable, repeatable contract flows that are expected to run in lanes/CI and during release validation.
3. If a scoped debug flow proves a recurring product risk, promote it into `tests/maestro/` and keep selectors/assertions deterministic.
