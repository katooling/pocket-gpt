# Android DX and Test Playbook

Last updated: 2026-03-05

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

Stage-2 profile examples:

```bash
bash scripts/dev/bench.sh stage2 --profile quick --device <device-id> --models 0.8b --resume --install-mode auto
bash scripts/dev/bench.sh stage2 --profile closure --device <device-id> --models both --scenarios both --install-mode auto
```

## Device Validation Loop

1. Run `python3 tools/devctl/main.py doctor` and resolve failures.
2. Run `bash scripts/dev/device-test.sh ...` for stage checks/baseline/loop/framework/reset sequence.
3. Store raw run artifacts under `scripts/benchmarks/runs/...`.
4. Link raw run artifacts from the matching `docs/operations/evidence/...` note.

`scripts/android/*` helpers are internal implementation details behind `devctl` and are not a stable interface for new contributors.

## Framework Lanes

1. Espresso: `python3 tools/devctl/main.py lane android-instrumented`
2. Maestro: `python3 tools/devctl/main.py lane maestro`
3. Combined real-runtime user journey gate: `python3 tools/devctl/main.py lane journey`
4. `android-instrumented` and `maestro` now default to native packaging + real-runtime preflight (model cache resolve, device push, provisioning sanity).
5. Maestro flow set includes Scenario A/B/C under `tests/maestro/` with checkpoint screenshots and failure debug bundles.
6. Device lane wrapper supports: `--framework espresso|maestro|both` (default `both`)
7. Device lanes now enforce a per-serial lock file under `scripts/benchmarks/device-env/locks/` to prevent concurrent run interference on shared phones.
8. Lock bypass is allowed only for manual break-glass debugging: `POCKETGPT_SKIP_DEVICE_LOCK=1`.

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

## Release-Candidate Real-Runtime App Path Lane

Run this lane only for release-candidate windows (requires both model paths):

```bash
adb shell am instrument -w \
  -e stage2_enable_app_path_test true \
  -e stage2_model_0_8b_path /absolute/device/path/qwen3.5-0.8b-q4.gguf \
  -e stage2_model_2b_path /absolute/device/path/qwen3.5-2b-q4.gguf \
  -e class com.pocketagent.android.RealRuntimeAppPathInstrumentationTest \
  com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner
```

This test validates:

1. app-path startup checks pass with provisioned models
2. runtime backend is `NATIVE_JNI`
3. user message stream returns a non-empty completion on real runtime

## Real-Runtime Journey Gate Artifacts

`devctl lane journey` writes deterministic artifacts under:

- `scripts/benchmarks/runs/YYYY-MM-DD/<device>/journey/<stamp>/journey-report.json`
- `scripts/benchmarks/runs/YYYY-MM-DD/<device>/journey/<stamp>/journey-summary.md`
- per-run screenshots/debug bundles/logcat under sibling directories

## Regression Rules (Fail Stage)

1. first-token latency regression beyond target band
2. new OOM or ANR in repeated runs
3. tool validation bypass or unsafe payload execution
4. policy allows network in offline-only mode
5. core UI workflows fail (`UI-01`..`UI-10`)
