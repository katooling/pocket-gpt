---
name: testing-android-maestro
description: Use when running, debugging, or triaging Android tests in the PocketGPT repo — Maestro flows, device-pinned lanes, targeted instrumented tests, screenshot-pack suites, selector drift, model-cache inspection, or device state issues. Prefer over raw adb or hand-written Gradle.
---

# Maestro Android CLI for PocketGPT

## When to Use This

Prefer `maestro-android` over raw `adb` or ad-hoc Gradle when you need:

- stable device selection (auto-resolves serial, warns on duplicate transports)
- structured artifacts per run (logcat, JUnit XML, crash signatures, failure hints)
- one-command scoped repro loops
- targeted instrumented runs without hand-writing `-P` runner-arg chains
- PocketGPT model-cache inspection under `Android/media`

Use raw `adb` only for coordinate taps, keyevents, or shell ops the CLI does not wrap.

## Fast Decision Tree

| Situation | Command | Why |
|---|---|---|
| Kotlin logic only | `bash scripts/dev/test.sh fast` | Fast compile + unit confidence |
| Compose/string/selectors changed | Above + `maestro-android lint` + `maestro-android audit-selectors` | Catch drift before long runs |
| Need repo lane on one phone | `maestro-android lane <name> --device <serial>` | Pins `ANDROID_SERIAL` for delegated lanes |
| One flaky UI/runtime path | `maestro-android scoped --flow tmp/repro.yaml [--no-build] [--no-install]` | Fast Maestro repro with artifacts |
| One instrumented class or method | `maestro-android scoped --type instrumented --device <serial> --test-class com.example.Test#method` | Short `connectedDebugAndroidTest` loop |
| Instrumented run needs runner args | Add `--runner-arg key=value` | Avoids long `-Pandroid.testInstrumentationRunnerArguments.*` |
| Screenshot-pack harness behavior | `--runner-arg screenshot_pack_dir=...` or `lane screenshot-pack --device <serial>` | Matches gated screenshot suites |
| Model/download cache inspection | `maestro-android device files --storage media models/` | PocketGPT persists model assets in `Android/media` |
| Device/app state triage | `maestro-android device info\|logcat\|ui` | Fast runtime/bootstrap triage |
| Not sure what to run | `maestro-android suggest` | Diff-based lane recommendation |

## Core Commands

- `lane <name> --device <serial>` — run a configured lane on one target device
- `scoped --flow tmp/repro.yaml` — one Maestro repro with logcat + artifacts
- `scoped --type instrumented --test-class Class[#method] --runner-arg key=value` — targeted device test without a dummy flow
- `device files|push --storage data|media ...` — inspect app-private or shared app-owned storage
- `device logcat --follow --filter REGEX` — stream app logcat
- `device ui` — dump resource ids, labels, and bounds
- `lint`, `audit-selectors`, `audit-testtags` — catch flow/testTag drift before widening
- `report latest`, `trace latest` — find the newest artifact bundle
- `suggest` — recommend lanes based on `git diff`

## PocketGPT-Specific Lessons

- **Android/media**: Persistent model assets live under `Android/media`, not `Android/data`. Use `--storage media` when checking seeded models, downloads, or multimodal companions.
- **clearState**: Use sparingly. It only resets app-private data and can invalidate seeded runtime state in `Android/media`.
- **Screenshot-pack gates**: Do not treat a generic `connectedDebugAndroidTest` failure as product evidence until you confirm the required runner args (`screenshot_pack_dir`, `screenshot_pack_fallback_dir`) were present.
- **Selector collisions**: When Maestro says an element is missing, run `device ui` first. Keyboard labels (e.g., `Send`) can collide with app labels. Prefer `id:` selectors.

## When Tests Fail: Triage

The CLI prints failure hints automatically. For manual triage:

| Symptom | Artifact | Likely cause |
|---|---|---|
| `FATAL EXCEPTION` | `logcat.txt` | App crash — read the stack trace |
| `Fatal signal` / `SIGSEGV` | `logcat.txt` | Native crash — check C++ backtrace |
| `Timeout waiting for` | `maestro-stderr.log` | Wrong selector or slow render — run `audit-selectors` |
| `No view found` | `maestro-stderr.log` | Missing element — check testTag via `audit-testtags` or `device ui` |
| `ANR in` | `logcat.txt` | App froze — blocking I/O on main thread |
| `OutOfMemoryError` | `logcat.txt` | Model too large or memory leak |
| Build failure | `gradle-stderr.log` | Kotlin compile error — fix code, not tests |
| Multiple devices error | CLI output | Duplicate ADB transports — pass `--device <serial>` |

## Workflow

1. Start with the lightest command that proves the risk.
2. If the failure is narrow, drop to `scoped` instead of rerunning a whole lane.
3. Read generated artifacts before guessing.
4. Re-run with `--no-build` or `--no-install` only when code/package inputs did not change.
5. Promote repeat repros from `tmp/` into stable flows or test classes.

## References

- [Testing map](references/testing-map.md) — canonical ladder, testTag inventory, refactor shortcuts
- [Command reference](references/command-reference.md) — concrete PocketGPT examples with real device serials
- [PocketGPT companion CLI guide](../../../docs/testing/maestro-android-companion-cli.md)
