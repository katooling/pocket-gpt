# Maestro Android CLI Reference

Use this when you need the concrete PocketGPT workflow for `maestro-android`.

## Setup

```bash
pipx install -e /path/to/maestro-android
cp /path/to/maestro-android/examples/pocket-gpt/maestro-android.pocket-gpt.yaml .maestro-android.yaml
```

## PocketGPT Workflow

- `doctor` checks Android tooling, `adb`, `maestro`, `gradlew`, and config presence.
- `lane smoke` runs the stable smoke flow set.
- `lane journey` and `lane screenshot-pack` inspect structured evidence outputs.
- `scoped` is the fast one-flow repro path for crashes, hangs, or runtime regressions.
- `report latest` finds the newest artifact bundle.
- `trace latest` shows the trace-capable bundle and `trace.json`.
- `cloud smoke` runs hosted smoke coverage.
- `cloud benchmark` runs hosted GPU-vs-CPU checks.
- `cloud status` polls Maestro Cloud upload ids.

## Scoped Flow Rules

- Put scoped repro flows in `tmp/`.
- Start the file with title and description comments.
- Use `--no-build --no-install` for fast reruns once the repro is stable.
- Use `--device` or `--serial` only when multiple devices are attached.

## When Not To Use It

- Do not replace canonical repo lanes with `scoped`.
- Do not use `scoped` as release evidence.
- Do not move PocketGPT-specific policy out of `devctl` unless the repo is intentionally standardizing it in the standalone CLI.
