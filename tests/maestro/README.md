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

Run on Maestro Cloud (supplemental path):

```bash
set -a
source .env
set +a
: "${MAESTRO_CLOUD_API_KEY:?Set MAESTRO_CLOUD_API_KEY in .env}"
./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
maestro cloud --android-api-level 34 --app-file "${APK_PATH}" --flows tests/maestro/
```

Single-flow run:

```bash
maestro cloud --android-api-level 34 --app-file "${APK_PATH}" --flows tests/maestro/scenario-a.yaml
```

Optional cloud flags:

1. `--project-id <project-id>` for orgs with multiple Maestro Cloud projects.
2. `--include-tags` / `--exclude-tags` when flow tags are introduced.
3. `--branch` / `--commit-sha` for CI traceability metadata.

Cloud-vs-local contract:

1. Cloud runs execute the same flow YAML files under `tests/maestro/`.
2. Cloud runs do not execute `devctl lane maestro` preflight (`prepare_real_runtime_env`, device lock, local artifact/logcat contract).
3. Keep `python3 tools/devctl/main.py lane maestro` as the required release-gate lane; use cloud as supplemental hosted coverage and fast feedback.

Flow contract notes:

1. All flows launch with `clearState: true` to prevent cross-scenario state leakage.
2. Screenshot checkpoints are consumed by `tests/ui-screenshots/inventory.yaml` during `lane screenshot-pack`.

Flows:

1. `scenario-a.yaml` - send-message chat loop smoke
2. `scenario-b.yaml` - simple-first unlock prelude + advanced controls/tools/diagnostics flow
3. `scenario-c.yaml` - continuity/image-aware journey flow
4. `scenario-activation-send-smoke.yaml` - unlock prelude + model setup activation + send recovery smoke
5. `scenario-first-run-download-chat.yaml` - clean-install first-run download, runtime ready transition, and send smoke
