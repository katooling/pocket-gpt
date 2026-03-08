# Dev Test Commands (Source of Truth)

This is the canonical command reference for local, CI, and device-lane execution.
All wrappers delegate to the config-driven orchestrator:

```bash
python3 tools/devctl/main.py <command> ...
```

## First 10 Minutes (New Joiner)

```bash
python3 -m pip install -r tools/devctl/requirements.txt
python3 tools/devctl/main.py doctor
bash scripts/dev/test.sh quick
```

If `doctor` fails, follow the suggested fix lines and rerun `doctor`.

## Newcomer Confidence Checklist (4 Commands)

Run these in order for fast, layered confidence:

```bash
python3 tools/devctl/main.py doctor
bash scripts/dev/test.sh quick
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
```

What each command proves:

1. `doctor`
   Environment/toolchain is usable (Python deps, Gradle/Android prerequisites, lane prerequisites).
2. `test.sh quick`
   Core JVM/Kotlin and policy/runtime contracts still hold after your changes.
3. `lane android-instrumented`
   App boots and bridge/runtime integration still works on-device/emulator instrumentation lane.
4. `lane maestro`
   Basic end-to-end UI smoke flow still passes in the external E2E runner.

Use this as a quick pre-PR baseline. For broader coverage, run `bash scripts/dev/test.sh ci` and, when needed, the physical device lane in this README.

## Standard (CI + Local)

```bash
bash scripts/dev/test.sh [fast|core|merge|auto|full|quick|ci]
```

Modes:

- `fast`: changed-file selected Gradle tasks + deterministic lane recommendations output (`build/devctl/recommended-lanes.txt`)
- `core`: full module unit tests + Android unit tests when SDK is configured, no clean
- `merge`: clean + full merge gate task set (CI-equivalent)
- `auto`: changed-file selected tasks with Android unit tests included when rules require them
- `quick`: compatibility alias for `core`
- `full`: compatibility alias for `merge`
- `ci`: compatibility alias for `merge`

Compatibility wrapper:

- `bash scripts/dev/verify.sh` (delegates to `test.sh ci`)

## Physical Device Lane

```bash
bash scripts/dev/device-test.sh [runs] [label] [-- <command...>] [--framework espresso|maestro|both]
```

Defaults:

1. Stage checks and baseline capture.
2. Benchmark settings apply.
3. 10-loop scenario command (`:apps:mobile-android-host:run`).
4. Optional framework lanes (`espresso` + `maestro` by default).
5. Benchmark settings reset on exit.

Examples:

- `bash scripts/dev/device-test.sh`
- `bash scripts/dev/device-test.sh 5 smoke-loop`
- `bash scripts/dev/device-test.sh 10 scenario-a -- ./gradlew --no-daemon :apps:mobile-android-host:run`
- `bash scripts/dev/device-test.sh 10 scenario-a --framework espresso`
- `bash scripts/dev/device-test.sh 10 scenario-a --framework maestro`

## Stage-2 Benchmark Wrapper

```bash
bash scripts/dev/bench.sh stage2 --device <device-id> [--date YYYY-MM-DD] [--profile quick|closure] [--models 0.8b|2b|both] [--scenarios a|b|both] [--resume] [--install-mode auto|force|skip] [--logcat filtered|full] [--evidence-note-path <abs-path>]
```

Required environment (device file paths for side-loaded models):

```bash
export POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH=/absolute/device/path/qwen3.5-0.8b-q4.gguf
export POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH=/absolute/device/path/qwen3.5-2b-q4.gguf
```

Contract outputs under `scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/`:

1. `scenario-a.csv`
2. `scenario-b.csv`
3. `stage-2-threshold-input.csv`
4. `model-2b-metrics.csv` (required for WP-12/ENG-13 closure evidence)
5. `meminfo-*.txt` (PSS snapshots per scenario/model)
6. `threshold-report.txt`
7. `runtime-evidence-validation.txt`
8. `logcat.txt`
9. `notes.md`
10. `summary.json`
11. `evidence-draft.md`

Evidence integrity gate:

```bash
python3 scripts/benchmarks/validate_stage2_runtime_evidence.py \
  scripts/benchmarks/runs/YYYY-MM-DD/<device-id>
```

Profile behavior:

1. `quick`:
   - defaults to `--models 0.8b`
   - uses low-run/token defaults for iteration
   - runs paired cold/warm measurements within the sweep (`runs=2` default) and records `warm_vs_cold_first_token_delta_ms`
   - supports `--resume` and partial model/scenario execution
   - threshold/runtime reports are still emitted; runtime evidence validator is not enforced as a closure gate
2. `closure`:
   - requires `--models both --scenarios both`
   - enforces strict threshold mode
   - enforces full runtime evidence validator gate

Install controls:

1. `--install-mode auto`: assemble APKs and skip reinstall when hashes are unchanged
2. `--install-mode force`: always install app + test APK
3. `--install-mode skip`: never install in this run

Optional cache controls (env):

1. `POCKETGPT_PREFIX_CACHE_ENABLED=0|1` (default `1`)
2. `POCKETGPT_PREFIX_CACHE_STRICT=0|1` (default `0`)
3. `POCKETGPT_RESPONSE_CACHE_TTL_SEC=<seconds>` (default `0`, disabled)
4. `POCKETGPT_RESPONSE_CACHE_MAX_ENTRIES=<count>` (default `0`, disabled)

## Framework Lanes (Direct)

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py lane journey [--repeats N]
```

Device-lock behavior:

1. These lanes now acquire an exclusive per-device lock under `scripts/benchmarks/device-env/locks/` to avoid concurrent uninstall/reinstall collisions.
2. Override only for emergency/manual troubleshooting: `POCKETGPT_SKIP_DEVICE_LOCK=1`.
3. Before lane execution, `devctl` runs device-health preflight (wake/unlock, `/data` utilization check, runtime-media storage probe, package-owner metadata check).
4. Runtime-media probe includes retry/backoff and fallback to `/sdcard/Download/<package>/...` when `/sdcard/Android/media/...` returns busy/resource errors.
5. Optional run owner metadata for journey reports:
   - `export POCKETGPT_RUN_OWNER='<your-name-or-handle>'`
6. Real-runtime provisioning auto-resolves the currently installed instrumentation runner before sanity probes (avoids flavor/package-name drift failures).
7. Model artifacts are re-pushed only when remote file size does not match the selected host artifact.

Wrapper:

```bash
bash scripts/dev/journey.sh [--repeats N]
```

Screenshot pack workflow:

```bash
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py lane screenshot-pack --update-reference
```

Maestro install:

```bash
curl -Ls https://get.maestro.mobile.dev | bash
```

## Maestro Cloud (Supplemental)

Use this when you want hosted device coverage without a local emulator/phone. This is supplemental to `python3 tools/devctl/main.py lane maestro`.

```bash
set -a
source .env
set +a
: "${MAESTRO_CLOUD_API_KEY:?Set MAESTRO_CLOUD_API_KEY in .env}"
./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
maestro cloud --android-api-level 34 --app-file "${APK_PATH}" --flows tests/maestro/
```

Optional:

1. Set project explicitly when needed: `--project-id <project-id>`
2. Filter by tags (if tags are added to flows): `--include-tags ...`, `--exclude-tags ...`
3. Add CI metadata: `--branch "$GITHUB_REF_NAME" --commit-sha "$GITHUB_SHA"`
4. Android device model selection is not deterministic in our current lane. As of March 8, 2026 with Maestro CLI `2.2.0`, Android cloud runs executed on `Pixel 6`; use `--android-api-level` as the reliable selector.

First-run flow gate command (writes JUnit report for CI/local triage):

```bash
maestro cloud --android-api-level 34 \
  --app-file "${APK_PATH}" \
  --flows tests/maestro/scenario-first-run-download-chat.yaml \
  --format junit \
  --output tmp/maestro-cloud-first-run.xml
```

Important:

1. `maestro cloud` runs the Maestro flow files directly.
2. It does not run `devctl` device health checks, real-runtime provisioning preflight, per-device lock handling, or local benchmark artifact/logcat capture contracts.
3. Keep `devctl lane maestro` and `devctl lane journey` as promotion/closure gates.

## Governance Commands

Wrappers remain callable, but all governance logic runs via `devctl governance`.

```bash
python3 tools/devctl/main.py governance docs-drift
python3 tools/devctl/main.py governance docs-health
python3 tools/devctl/main.py governance screenshot-inventory-check
python3 tools/devctl/main.py governance evidence-check docs/operations/evidence/wp-xx/YYYY-MM-DD-note.md
python3 tools/devctl/main.py governance evidence-check-changed
python3 tools/devctl/main.py governance validate-pr-body /tmp/pr-body.md
python3 tools/devctl/main.py governance stage-close-gate /tmp/pr-body.md
python3 tools/devctl/main.py governance self-test
```
