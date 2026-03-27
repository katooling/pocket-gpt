# Maestro Android Companion CLI

`maestro-android` is now treated as an external companion CLI instead of an in-repo tool. Keep the standalone checkout in a separate path and install from that checkout until you switch to the GitHub release/tag flow.

## Local install on this machine

```bash
python3 -m pip install --user pipx
pipx ensurepath
pipx install -e /path/to/maestro-android
maestro-android init
```

For a released tag from GitHub:

```bash
pipx install git+https://github.com/kalibraring/maestro-android.git@vX.Y.Z
```

## Skill placement

This repo keeps the PocketGPT-specific skill at `.claude/skills/maestro-android-cli/`.

- Use the repo-local copy for project context and shared team behavior.
- Mirror to `~/.claude/skills/` or `~/.codex/skills/` only when you want a personal, machine-local install across all repos.
- Keep one canonical `SKILL.md` and reference it from the tool-specific directories rather than rewriting the same guidance in multiple places.
- For skills.sh publishing, keep the same skill folder in a standalone repo and install it with `npx skills add <owner/repo> --skill maestro-android-cli`.

## Pocket-GPT setup

From the Pocket-GPT repo root:

```bash
cp /path/to/maestro-android/examples/pocket-gpt/maestro-android.pocket-gpt.yaml .maestro-android.yaml
maestro-android doctor
```

That example config preserves the repo’s current operating model:

- Tests preserve app sandbox state by default. Only use `--clear-state` for flows that specifically need a fresh app-private sandbox.
- Treat shared/external storage as the canonical model cache location. Do not rely on app-private storage for the only copy of a model if you want it to survive resets.
- `lane smoke|journey|screenshot-pack|fast-smoke|valid-output|strict-journey` delegate to `devctl`
- `lane lifecycle` delegates to `scripts/ci/run_lifecycle_e2e.sh`
- `lane cloud-smoke` delegates to `scripts/dev/maestro-cloud-smoke.sh`
- `maestro-android init` writes a starter `.maestro-android.yaml` if you want to bootstrap the generic tool first
- `cloud smoke` runs the repo’s hosted smoke loop directly from the standalone CLI
- `cloud benchmark` runs the hosted GPU-vs-CPU benchmark loop directly
- `cloud status` replaces the upload-status polling shell helper
- `scoped` remains the fast one-flow repro path, but now runs through the external CLI with the same `tmp/` and title/description flow convention

See `.claude/skills/maestro-android-cli/references/testing-map.md` for the canonical testing ladder.

## Example usage

```bash
maestro-android lane smoke
maestro-android lane journey -- --repeats 2
maestro-android lane screenshot-pack
maestro-android scoped --flow tmp/maestro-repro.yaml
maestro-android report latest
maestro-android trace latest
maestro-android cloud smoke
maestro-android cloud benchmark
maestro-android cloud status label:upload-id
```

## Publication path

After the standalone project is pushed to GitHub, prefer a tag-driven release:

```bash
git -C /path/to/maestro-android tag vX.Y.Z
git -C /path/to/maestro-android push origin vX.Y.Z
```

GitHub Actions will build and attach the wheel/sdist to the release automatically. Other machines can then install with `pipx install git+https://github.com/kalibraring/maestro-android.git@vX.Y.Z`.

Use this companion CLI for local Android/Maestro orchestration and report lookup. Continue using the canonical repo lanes and gate wrappers for merge and release evidence.
