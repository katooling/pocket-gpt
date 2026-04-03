---
name: testing-android-maestro
description: Orchestrates PocketGPT Android testing via the maestro-android CLI. Covers repo lanes, scoped Maestro repros, targeted connectedAndroidTest selection with runner args, selector audits, artifact lookup, and device debugging for runtime/bootstrap issues. Use for Maestro flows, Android device tests, screenshot-pack work, selector drift, model-cache inspection, or when you need to pin a specific device serial.
---

# Maestro Android CLI for PocketGPT

## Use This First

Prefer `maestro-android` over raw `adb` or ad-hoc Gradle when you need:

- stable device selection
- structured artifacts and triage hints
- one-command scoped repro loops
- targeted instrumented runs without hand-writing long runner-arg commands
- PocketGPT model-cache inspection under `Android/media`

Use raw `adb` only for coordinate taps, keyevents, or shell operations the CLI still does not wrap.

## Fast Decision Tree

| Situation | Command | Why |
|---|---|---|
| Kotlin logic only | `bash scripts/dev/test.sh fast` | Fast compile + unit confidence |
| Compose/string/selectors changed | Above + `maestro-android lint` + `maestro-android audit-selectors` | Catch drift before long runs |
| Need repo lane on one phone | `maestro-android lane <name> --device <serial>` | Pins `ANDROID_SERIAL` for delegated lanes |
| One flaky UI/runtime path | `maestro-android scoped --flow tmp/repro.yaml [--no-build] [--no-install]` | Fast Maestro repro with artifacts |
| One instrumented class or method | `maestro-android scoped --type instrumented --device <serial> --test-class com.example.Test#method` | Short `connectedDebugAndroidTest` loop |
| Instrumented run needs runner args | Add `--runner-arg key=value` | Avoid long manual `-Pandroid.testInstrumentationRunnerArguments.*` commands |
| Need screenshot-pack-only harness behavior | `maestro-android lane screenshot-pack --device <serial>` or instrumented scoped with `--runner-arg screenshot_pack_dir=...` | Matches gated screenshot suites |
| Need model/download cache inspection | `maestro-android device files --storage media models/` | PocketGPT stores persistent model assets in `Android/media` |
| Need current device/app state | `maestro-android device info|logcat|ui` | Fast runtime/bootstrap triage |

## Core Commands

- `lane <name> --device <serial>` runs a configured lane on one target device
- `scoped --flow tmp/repro.yaml` runs one Maestro repro with logcat + artifacts
- `scoped --type instrumented --test-class Class[#method] --runner-arg key=value` runs a targeted device test without a dummy flow
- `device files|push --storage data|media ...` inspects app-private or shared app-owned storage
- `device logcat --follow --filter REGEX` streams app logcat
- `device ui` dumps current resource ids, labels, and bounds
- `lint`, `audit-selectors`, `audit-testtags` catch flow/testTag drift before widening
- `report latest` and `trace latest` find the newest artifact bundle

## PocketGPT-Specific Lessons

- Persistent model assets live under `Android/media`, not just `Android/data`; use `--storage media` when checking seeded models, downloads, or multimodal companions.
- Use `clearState` sparingly. It is not the same as a full external-storage reset and can invalidate assumptions about seeded runtime state.
- For screenshot-pack and other harness-gated suites, do not treat a generic `connectedDebugAndroidTest` failure as product evidence until you confirm the required runner args were present.
- When Maestro says an element is missing, check `device ui` and prefer `id:` selectors over text. Keyboard labels can collide with app labels such as `Send`.

## Workflow

1. Start with the lightest command that proves the risk.
2. If the failure is narrow, drop to `scoped` instead of rerunning a whole lane.
3. Read the generated artifacts before guessing.
4. Re-run with `--no-build` or `--no-install` only when code/package inputs did not change.
5. Promote repeat repros from `tmp/` into stable flows or suites.

## References

- [Testing map](references/testing-map.md)
- [Command reference](references/command-reference.md)
- [PocketGPT companion CLI guide](../../../docs/testing/maestro-android-companion-cli.md)
