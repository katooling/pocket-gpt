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
```

Flows:

1. `scenario-a.yaml` - send-message chat loop smoke
2. `scenario-b.yaml` - advanced controls, tools, and diagnostics flow
3. `scenario-c.yaml` - continuity/image-aware journey flow
4. `scenario-activation-send-smoke.yaml` - model setup activation + send recovery smoke
