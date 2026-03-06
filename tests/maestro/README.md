# Maestro Flows

Maestro E2E flows for Android-device smoke and release-journey validation.

Source of truth for lane execution commands: `scripts/dev/README.md`.

Install (validated against `v1.39.13`):

```bash
curl -Ls https://get.maestro.mobile.dev | bash
```

Run via orchestrator:

```bash
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane screenshot-pack
```

Flow contract notes:

1. All flows launch with `clearState: true` to prevent cross-scenario state leakage.
2. Screenshot checkpoints are consumed by `tests/ui-screenshots/inventory.yaml` during `lane screenshot-pack`.

Flows:

1. `scenario-a.yaml` - send-message chat loop smoke
2. `scenario-b.yaml` - simple-first unlock prelude + advanced controls/tools/diagnostics flow
3. `scenario-c.yaml` - continuity/image-aware journey flow
4. `scenario-activation-send-smoke.yaml` - unlock prelude + model setup activation + send recovery smoke
