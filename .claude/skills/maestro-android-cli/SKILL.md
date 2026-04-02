---
name: testing-android-maestro
description: Orchestrates PocketGPT Android testing via the maestro-android CLI. Handles lane runs, scoped repros, flow linting, selector and testTag audits, artifact inspection, cloud smoke and benchmark runs, and failure triage. Also documents when raw adb is the right tool instead. Triggers on testing, maestro, lane, scoped, flow, smoke, audit-selectors, audit-testtags, report, cloud, test failure, device error, or device debugging.
---

# Maestro Android CLI for PocketGPT

## Why This Tool (Not Raw adb/maestro)

Reproducible device selection (auto-detects serial, warns on duplicate transports), automatic build/install, structured artifacts per flow (logcat + JUnit + debug output), crash signature scanning, failure diagnosis hints, pass/fail summary tables, and consistent lane definitions shared across team and CI.

## When Raw adb Is Still Needed

Most device debugging is covered by `maestro-android device *` commands (see below).
Use raw adb directly only for tasks the CLI doesn't wrap:

| Task | Raw adb command |
|---|---|
| Tap/input without a flow | `adb shell input tap X Y` / `adb shell input text "..."` |
| Wake/screen control | `adb shell input keyevent KEYCODE_WAKEUP` |
| Pull files from device | `adb pull /sdcard/.../file local/` |

**Rule of thumb:** Use the CLI for both test orchestration and device debugging. Fall back to raw adb only for coordinate-based input and power management.

## Decision Tree: What To Run

| What changed? | Command | Why |
|---|---|---|
| Kotlin logic only (no UI) | `bash scripts/dev/test.sh fast` | Compile + unit tests |
| UI composables or strings.xml | Above + `maestro-android lane smoke` | Catch selector breakage |
| Native C++ / JNI | Above + `devctl lane android-instrumented` + `devctl lane journey` | Bridge + runtime validation |
| Maestro flow YAML | `maestro-android lint` + `maestro-android audit-selectors` | Flow health |
| Pre-merge (any change) | `bash scripts/dev/test.sh merge` + `maestro-android lane smoke` | Broad confidence |
| Debugging specific failure | `maestro-android scoped --flow tmp/repro.yaml` | Fast one-flow repro |
| Debugging instrumented test | `maestro-android scoped --type instrumented --test-class com.example.Test` | Gradle test with artifacts |
| Debugging device state | `maestro-android device info/files/logcat/ui` | App-aware device inspection |
| Not sure what to run | `maestro-android suggest` | Auto-suggest based on git diff |

## Setup

```bash
pipx install -e /path/to/maestro-android
cp /path/to/maestro-android/examples/pocket-gpt/maestro-android.pocket-gpt.yaml .maestro-android.yaml
maestro-android doctor
```

## Core Commands

- `doctor` -- verify adb, maestro, gradlew, config
- `devices` -- list connected adb devices with serial, state, and details
- `device files [path]` -- list files in app's external data directory
- `device push <local> [dest]` -- push a file to app's external data directory
- `device logcat [--filter REGEX] [--follow] [--lines N] [--save FILE]` -- app-filtered logcat
- `device ui` -- dump UI hierarchy as a structured table (resource-ids, text, bounds)
- `device info` -- show app process status, PID, CPU, and memory
- `lane smoke|journey|screenshot-pack` -- run configured lanes
- `scoped --flow tmp/repro.yaml` -- one-flow repro with crash scanning
- `scoped --type instrumented|unit` -- Gradle test with structured artifacts
- `suggest` -- recommend lanes based on `git diff`
- `lint` / `audit-selectors` / `audit-testtags` -- flow and selector health
- `report latest` / `trace latest` -- inspect artifacts
- `clean --stale-flows --confirm` -- prune old tmp flows
- `cloud smoke|benchmark|status` -- hosted workflows

## Device Debugging Recipes

Common PocketGPT debugging scenarios using `maestro-android device`:

```bash
# Verify model files on device
maestro-android device files models/

# Monitor multimodal/inference pipeline live (Ctrl-C to stop)
maestro-android device logcat --follow --filter "MULTIMODAL|SendMessage|PocketLlama"

# Dump last 50 logcat lines matching a crash pattern
maestro-android device logcat --filter "FATAL|SIGSEGV|ANR" --lines 50

# Save filtered logcat to a file for later analysis
maestro-android device logcat --filter "RuntimeOrchestrator" --save tmp/orchestrator.log

# Inspect current UI state (testTags, bounds, text)
maestro-android device ui

# Push a supplementary model file (e.g. mmproj for multimodal)
maestro-android device push mmproj-q8_0.gguf models/

# Check app process status, CPU, and memory
maestro-android device info

# Target a specific device when multiple are connected
maestro-android device --device 192.168.1.43:43021 logcat --follow
```

## Feedback Loop

After any test run:
1. Read the pass/fail summary table printed by the CLI
2. For failures, follow the hint printed below the table
3. Fix code → re-run with `--no-build` if only flow changed, or rebuild if code changed
4. Repeat until all flows pass
5. Promote recurring failures into stable tests under `tests/maestro/`

## When Tests Fail: Triage

The CLI prints failure hints automatically. For manual triage:

| Symptom | Artifact | Likely cause |
|---|---|---|
| `FATAL EXCEPTION` | `logcat.txt` | App crash — read the stack trace |
| `Fatal signal` / `SIGSEGV` | `logcat.txt` | Native crash — check C++ backtrace |
| `Timeout waiting for` | `maestro-stderr.log` | Wrong selector or slow render — run `audit-selectors` |
| `No view found` | `maestro-stderr.log` | Missing element — check testTag spelling via `audit-testtags` |
| `ANR in` | `logcat.txt` | App froze — blocking I/O on main thread |
| `OutOfMemoryError` | `logcat.txt` | Model too large or memory leak |
| Build failure | `gradle-stderr.log` | Kotlin compile error — fix code, not tests |
| Multiple devices error | CLI output | Duplicate ADB transports — pass `--device <serial>` |
| `CONFIG_ERROR: Schema validation failed` | CLI output | `.maestro-android.yaml` has invalid fields — re-sync from `examples/pocket-gpt/` in the maestro-android library |
| Model says "no image" | Live logcat | Check `MULTIMODAL_INIT` and `MULTIMODAL_DECISION` logs — likely mmproj missing or marker mismatch |

## Config Reference

The canonical config for this project lives in the maestro-android library:
`/Users/mkamar/Non_Work/Projects/maestro-android/examples/pocket-gpt/maestro-android.pocket-gpt.yaml`

If the project's `.maestro-android.yaml` drifts (e.g. after pulling changes), re-sync from that file.
Valid lane `kind` values are `"test"` and `"command"` only. All lanes in this project use `"command"` with a `python3 tools/devctl/main.py lane <name>` or `bash scripts/...` argv.

## References

- [testTag inventory](references/testing-map.md) -- auto-audit with `maestro-android audit-testtags`
- [PocketGPT companion CLI guide](../../../docs/testing/maestro-android-companion-cli.md)
