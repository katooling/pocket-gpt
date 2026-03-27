---
name: maestro-android-cli
description: Use when working on PocketGPT Android testing with the external maestro-android CLI, including doctor, lane, scoped repros, report or trace lookup, cloud smoke or benchmark runs, and config setup.
---

# Maestro Android CLI for PocketGPT

Use this skill when you need context on how PocketGPT tests Android flows with the standalone `maestro-android` companion CLI.

## When To Use This Skill

- Run or debug Maestro flows for PocketGPT.
- Set up or update `.maestro-android.yaml` in this repo.
- Scaffold a starter config with `maestro-android init`.
- Use `doctor`, `lane`, `scoped`, `report`, `trace`, or `cloud`.
- Decide whether a task belongs in the standalone CLI or in repo-native `devctl`.
- Prepare a skill-aware workflow for other AI tools or agents.

## Core Model

PocketGPT uses two layers:

1. `maestro-android` for Android-project orchestration, scoped repros, and artifact lookup.
2. Repo-native `devctl` lanes for the canonical project gates and richer repo-specific wrappers.

Keep that split. Use the external CLI to make testing repeatable across Android projects, then keep PocketGPT-specific gate logic in this repo.
See `references/testing-map.md` for the recommended testing ladder and when to use each command.

## Primary Commands

- `maestro-android doctor`
- `maestro-android init`
- `maestro-android lane smoke`
- `maestro-android lane journey`
- `maestro-android lane screenshot-pack`
- `maestro-android lane lifecycle`
- `maestro-android scoped --flow tmp/maestro-repro.yaml`
- `maestro-android report latest`
- `maestro-android trace latest`
- `maestro-android cloud smoke`
- `maestro-android cloud benchmark`
- `maestro-android cloud status label:upload-id`
- `maestro-android merge-reports --out build/merged run-a run-b`

## PocketGPT Setup

- Install the standalone CLI with `pipx`.
- Use `maestro-android init` for a generic starter config, or copy `examples/pocket-gpt/maestro-android.pocket-gpt.yaml` into the repo root as `.maestro-android.yaml` for the full PocketGPT lane map.
- Keep scoped repro flows under `tmp/` and start them with title and description comments.
- Prefer `lane smoke|journey|screenshot-pack|lifecycle` for stable workflows and `scoped` only for targeted debugging.
- Use `cloud smoke` for hosted coverage, `cloud benchmark` for hosted GPU-vs-CPU checks, and `cloud status` for upload polling.

## References

- [PocketGPT testing map](references/testing-map.md)
- [PocketGPT companion CLI guide](../../../docs/testing/maestro-android-companion-cli.md)
- [PocketGPT dev command reference](../../../scripts/dev/README.md)
- [PocketGPT testing index](../../../docs/testing/README.md)
