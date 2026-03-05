# Android DX and Test Playbook

Last updated: 2026-03-06

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
4. Canonical stuck-reply gate command:
   - `python3 tools/devctl/main.py lane journey --repeats 1 --reply-timeout-seconds 90`
5. `lane journey` supports deterministic send diagnostics:
   - `--reply-timeout-seconds` (default `90`)
   - `--capture-intervals` (default `5,15,30,60,90`; `t+0` and timeout are always captured)
   - `--prompt` (default probe prompt: `"ola, how you doin"`)
6. `android-instrumented` and `maestro` now default to native packaging + real-runtime preflight (model cache resolve, device push, provisioning sanity).
7. Maestro flow set includes Scenario A/B/C under `tests/maestro/` with checkpoint screenshots and failure debug bundles.
8. Device lane wrapper supports: `--framework espresso|maestro|both` (default `both`)
9. Device lanes now enforce a per-serial lock file under `scripts/benchmarks/device-env/locks/` to prevent concurrent run interference on shared phones.
10. Lock bypass is allowed only for manual break-glass debugging: `POCKETGPT_SKIP_DEVICE_LOCK=1`.
11. Device lanes run health preflight before execution:
   - wake/unlock attempt
   - `/data` utilization guard
   - writable runtime-media probe under app media path (with retry/fallback to `/sdcard/Download/<package>/...` for busy media-path edge cases)
   - installed package owner metadata check (`dumpsys package`)
12. Journey reports include run-owner metadata (`POCKETGPT_RUN_OWNER`, host).
13. Real-runtime provisioning resolves the installed instrumentation runner dynamically to avoid flavor-specific package mismatch.
14. Model preflight uses persistent on-device cache manifest `model-sync-v1.json`:
   - primary path: `/sdcard/Android/media/<app>/devctl-cache/model-sync-v1.json`
   - fallback path: `/sdcard/Download/<app>/devctl-cache/model-sync-v1.json`
   - each lane run still performs provisioning instrumentation probe.
15. Cache decisions are written to `real-runtime-preflight.json` (`cache_hit`, `size_probe_hit`, `push_required`, `forced_sync`) for operator auditability.
16. Debug override: set `POCKETGPT_FORCE_MODEL_SYNC=1` to force model push even when cache matches.

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

For each run, the send-capture stage includes:

1. Timed screenshots (`t+0`, configured checkpoints, timeout checkpoint)
2. Timed UI hierarchy dumps (`window_dump_tXXX.xml`)
3. App-scoped snapshots (`run-as` shared prefs for chat/runtime model/download state)
4. Send-window logcat slice
5. `journey-report.json` step fields:
   - `phase` (`startup|send|first_token|completed|timeout|error`)
   - `elapsed_ms`
   - `runtime_status`
   - `backend`
   - `active_model_id`
   - `placeholder_visible`
   - `response_visible`
   - `response_role`
   - `response_non_empty`
   - `first_token_seen`
   - `request_id`
   - `finish_reason`
   - `terminal_event_seen`
   - `first_token_ms`
   - `completion_ms`

Interpretation rubric for send-capture:

1. Pass: `phase=completed`, `placeholder_visible=false`, `response_non_empty=true`, `terminal_event_seen=true`.
   Runtime may transiently show `Loading` immediately after completion; use the bounded post-completion grace window.
2. Timeout fail: `phase=timeout`, placeholder or loading persists at SLA.
3. First-token fail: `phase=first_token`, tokens started but no terminal event by SLA (`no_terminal_event` / `cancel_ack_missing`).
4. Error fail: `phase=error` with failure signature + kickoff/debug output path.

## Regression Rules (Fail Stage)

1. first-token latency regression beyond target band
2. new OOM or ANR in repeated runs
3. tool validation bypass or unsafe payload execution
4. policy allows network in offline-only mode
5. core UI workflows fail (`UI-01`..`UI-10`)
