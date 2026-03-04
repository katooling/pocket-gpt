# Maestro Flows

Maestro E2E flows for Android-device smoke validation.

Install (validated against `v1.39.13`):

```bash
curl -Ls https://get.maestro.mobile.dev | bash
```

Then run via orchestrator:

```bash
python3 tools/devctl/main.py lane maestro
```

Flows:

1. `scenario-a.yaml` - send-message chat loop smoke
2. `scenario-b.yaml` - advanced controls and diagnostics action smoke
