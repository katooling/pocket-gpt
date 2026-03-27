# Testing Runbooks

Last updated: 2026-03-10

These runbooks are short task guides. Strategy and gates stay in `docs/testing/test-strategy.md`.

## Runbook: Fast Engineer Loop

```bash
python3 tools/devctl/main.py doctor
bash scripts/dev/test.sh fast
```

Then execute any recommended follow-up lanes from `build/devctl/recommended-lanes.txt`.

## Runbook: Merge Readiness

```bash
bash scripts/dev/test.sh merge
python3 tools/devctl/main.py governance docs-health
python3 tools/devctl/main.py governance docs-accuracy
```

## Runbook: Merge Unblock Gate

```bash
python3 tools/devctl/main.py gate merge-unblock
```

Risk labels can be provided explicitly when triaging PR-equivalent risk locally:

```bash
python3 tools/devctl/main.py gate merge-unblock --risk-label risk:runtime
```

## Runbook: Promotion Gate

```bash
python3 tools/devctl/main.py gate promotion
```

Include screenshot contract checks when needed:

```bash
python3 tools/devctl/main.py gate promotion --include-screenshot-pack
```

## Runbook: Android UI/Runtime Smoke

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane maestro --include-tags smoke
python3 tools/devctl/main.py lane maestro --include-tags model-management
```

## Runbook: Local Lifecycle E2E (First-Run Download -> Chat)

```bash
./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=false :apps:mobile-android:assembleDebug
APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
adb install -r "${APK_PATH}"
maestro --device "$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')" test tests/maestro/scenario-first-run-download-chat.yaml
```

## Runbook: Scoped Device Crash Repro (Maestro + Logcat Fast Loop)

Use this when debugging a specific runtime crash/hang on one device. Keep the flow minimal and local under `tmp/`.

```bash
bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml
```

Speed loop options:

1. Re-run without rebuilding/installing app:
   - `bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml --no-build --no-install`
2. Target a specific device:
   - `bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml --serial <device-id>`
3. Override crash signature regex:
   - `bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml --pattern "<regex>"`

Promotion rule:

1. After fix confirmation, add or update a stable flow under `tests/maestro/` if the journey is a recurring risk.
2. Add targeted unit/contract tests for the logic branch that caused the failure.
3. Run canonical lanes (`fast`, and risk-appropriate `android-instrumented`/`maestro`/`journey`) before merge.

## Runbook: High-Risk PR Verification

Use when PR carries `risk:e2e-lifecycle`, `risk:runtime`, `risk:provisioning`, or touches high-risk runtime/provisioning/chat paths.

```bash
bash scripts/dev/test.sh merge
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
python3 tools/devctl/main.py lane journey --steps instrumentation,send-capture,maestro
maestro --device "$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')" test tests/maestro/scenario-first-run-download-chat.yaml
```

## Runbook: Main-Push Blocking Lifecycle Check (CI Equivalent)

```bash
./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=false :apps:mobile-android:assembleDebug
APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
adb install -r "${APK_PATH}"
maestro --format junit --device "$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')" test tests/maestro/scenario-first-run-download-chat.yaml > tmp/lifecycle-e2e-first-run-local.xml
```

For CI-equivalent crash-signature guard behavior (logcat scan for app `SIGSEGV`/abort/fatal patterns), run:

```bash
bash scripts/ci/run_lifecycle_e2e.sh local-manual
```

## Runbook: Cloud Smoke (Supplemental)

```bash
bash scripts/dev/maestro-cloud-smoke.sh
```

This runs only flows tagged `cloud-smoke` under `tests/maestro-cloud/`.
Keep long-running hosted benchmarks in dedicated scripts such as `bash scripts/dev/maestro-cloud-gpu-benchmark.sh`.

## Runbook: Cloud GPU vs CPU Benchmark (Supplemental)

Runs a minimal first-run hosted-device benchmark that requires GPU qualification to succeed, then compares GPU-on vs GPU-off send duration on the same cloud device.

```bash
bash scripts/dev/maestro-cloud-gpu-benchmark.sh
```

Single API level:

```bash
bash scripts/dev/maestro-cloud-gpu-benchmark.sh --api-level 34
```

## Runbook: Runtime Tuning Analysis

Use this when validating learned runtime recommendations or explaining a regression after a device run.

```bash
bash scripts/dev/test.sh fast
python3 tools/devctl/main.py lane journey --repeats 3 --mode strict --reply-timeout-seconds 90
```

Or run the benchmark sweep:

```bash
bash scripts/dev/bench.sh stage2 --device <device-id> --profile quick --models 0.8b --scenarios a
```

Then:

1. Export diagnostics from the app's advanced settings sheet.
2. In the diagnostic message, inspect `RUNTIME_TUNING|...`, `RUNTIME_TUNING_SAMPLE|...`, `RUNTIME_RESIDENCY|...`, and `PREFIX_CACHE_DIAG|...`.
3. Correlate them with files under `scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/` for stage-2 benchmark sweeps or `tmp/devctl-artifacts/YYYY-MM-DD/<device-id>/journey/<stamp>/` for `devctl lane journey` runs:
   - `summary.json`
   - `runtime-log-signals.md`
   - `scenario-a.csv` / `scenario-b.csv`
   - `meminfo-*.txt`
   - `logcat.txt`
4. Use `runtime-log-signals.md` as the first-pass summary for `MMAP|`, `FLASH_ATTN|`, `SPECULATIVE|`, and `PREFIX_CACHE|` issues; then use `docs/testing/runtime-tuning-debugging.md` to decide whether a promotion or demotion was correct.
5. When TTFT or decode throughput still looks wrong, inspect the matching logcat window for `MMAP|`, `FLASH_ATTN|`, `SPECULATIVE|`, `PREFIX_CACHE|`, and `PROMPT_TRIM|`.
6. When the regression appears only after switching conversations, inspect raw logcat for `PREFIX_CACHE|stage=store_state` and `PREFIX_CACHE|stage=restore_state`. `success=true` confirms real slot-state reuse; `reason=over_budget` or `reason=empty` explains why switch-back fell back to re-decode.

## Runbook: Send/Runtime Journey Validation

Artifacts to check after the lane completes:

1. `journey-report.json`
2. `journey-summary.md`
3. each send-window `*-runtime-log-signals.md` linked from the journey summary
4. the original send-window logcat if the summarized finding needs raw-line confirmation


```bash
python3 tools/devctl/main.py lane journey --repeats 1 --mode strict --reply-timeout-seconds 90
```

Use `--mode valid-output` for slower devices where terminal output validation is required over SLA-oriented strictness.

If you only need to inspect the latest output, use the report helper:

```bash
python3 tools/devctl/main.py report journey
```

## Runbook: Screenshot Contract Validation

```bash
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py governance screenshot-inventory-check
```

For the latest screenshot inventory bundle:

```bash
python3 tools/devctl/main.py report screenshot-pack
```

If the goal is promotion triage and known harness-noise signatures should be treated as non-blocking caveats:

```bash
python3 tools/devctl/main.py lane screenshot-pack --product-signal-only
```

## Runbook: Stage-2 Runtime Closure

```bash
bash scripts/dev/bench.sh stage2 --profile closure --device <device-id> --models both --scenarios both --install-mode auto
```

Attach evidence note and run:

```bash
python3 tools/devctl/main.py governance evidence-check docs/operations/evidence/wp-xx/YYYY-MM-DD-note.md
```
