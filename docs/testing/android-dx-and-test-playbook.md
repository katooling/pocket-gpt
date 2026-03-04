# Android DX and Test Playbook

Last updated: 2026-03-04

## Source of truth

- Command contract: `scripts/dev/README.md`
- Strategy/release gates: `docs/testing/test-strategy.md`

## Purpose

Define the Android development + physical-device validation workflow with minimal ambiguity.

## Environment Prerequisites

1. JDK 17+
2. Android SDK platform-tools (`adb`)
3. One physical Android device with USB debugging enabled
4. USB debugging RSA authorization accepted on-device

## Canonical Commands

Use only commands documented in `scripts/dev/README.md` for:

1. Standard local/CI tests
2. Physical-device lane
3. Stage-2 benchmark wrapper
4. Framework lanes (`android-instrumented`, `maestro`)
5. Governance checks (`docs-drift-check`, `evidence-check`)

## Device Validation Loop

1. Run `python3 tools/devctl/main.py doctor` and resolve failures.
2. Run `bash scripts/dev/device-test.sh ...` for stage checks/baseline/loop/framework/reset sequence.
3. Store raw run artifacts under `scripts/benchmarks/runs/...`.
4. Link raw run artifacts from the matching `docs/operations/evidence/...` note.

`scripts/android/*` helpers are internal implementation details behind `devctl` and are not a stable interface for new contributors.

## Framework Lanes

1. Espresso: `python3 tools/devctl/main.py lane android-instrumented`
2. Maestro: `python3 tools/devctl/main.py lane maestro`
3. Device lane wrapper supports: `--framework espresso|maestro|both` (default `both`)

Maestro install (validated against `v1.39.13`):

```bash
curl -Ls https://get.maestro.mobile.dev | bash
```

## WP-11 UI Validation Loop

Use this loop once Compose UI changes land:

1. Run JVM UI tests:
   - `./gradlew --no-daemon -Pkotlin.incremental=false :apps:mobile-android:testDebugUnitTest`
2. Compile instrumentation lane:
   - `./gradlew --no-daemon -Pkotlin.incremental=false :apps:mobile-android:compileDebugAndroidTestKotlin`
3. Run instrumentation + Maestro on connected device:
   - `python3 tools/devctl/main.py lane android-instrumented`
   - `python3 tools/devctl/main.py lane maestro`
4. Capture logs and summary under deterministic run path:
   - `scripts/benchmarks/runs/YYYY-MM-DD/<device>/wp-11-ui-acceptance/...`
5. Publish evidence note under:
   - `docs/operations/evidence/wp-11/YYYY-MM-DD-*.md`

## Regression Rules (Fail Stage)

1. first-token latency regression beyond target band
2. new OOM or ANR in repeated runs
3. tool validation bypass or unsafe payload execution
4. policy allows network in offline-only mode
5. core UI workflows fail (`UI-01`..`UI-10`)
