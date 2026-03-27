# Agent Instructions (PocketGPT)

This file defines repository-specific guidance for AI/code agents.
This repo follows engineering excellence:
- All agents are responsible Senior Engineers that clean up dirtry code when it's closeby to the area they are working on. It's never wrong to clean up.
- When agents observe repeating operations/commands/processes they shall abstract them as needed by creating reusable scripts and functions to ease future maintenance for other devs.
- When compatible model files already exist in `Downloads/<package>/models`, use in-app import instead of triggering a new model download.

## Primary Testing Rule

1. Use canonical lanes for broad confidence and merge/release safety:
   - `bash scripts/dev/test.sh fast|merge`
   - `python3 tools/devctl/main.py lane android-instrumented|maestro|journey|screenshot-pack`
2. Use scoped on-demand device flows only for targeted debugging (single crash/hang/regression path), not as a replacement for merge/release gates.
3. For Android testing context, read `.claude/skills/maestro-android-cli/SKILL.md` and `docs/testing/maestro-android-companion-cli.md` before making changes to test workflows.

## Scoped On-Demand Device Flow (When And How)

Use this approach when all are true:

1. A failure is device-specific or runtime-specific.
2. You can isolate it to a short user journey.
3. Fast reproduce + immediate log inspection is more valuable than full-lane fan-out.

Search in the existing minimal flows in first 2 lines of the files to see if your case is covered or mostly covered.
- If yes, modify it to fit your exact case.
- If not, create a minimal Maestro flow in `tmp/` that performs only the failing path (ensure to include title and description the first 2 lines).
Use `bash scripts/dev/scoped-repro.sh --flow tmp/<scoped-flow>.yaml` for this loop.
The script builds/installs, runs the flow, captures Maestro output + logcat under `tmp/`, and scans for crash/runtime signatures with app-context filtering. 
Use `--no-build --no-install` for fast reruns and `--serial <device-id>` for multi-device setups.

Do not use scoped repro alone as release evidence. Promote recurring risks into stable tests under `tests/maestro/` and run canonical lanes afterward.
