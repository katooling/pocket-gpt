# Dev Test Commands (Source of Truth)

This is the canonical command reference for local, CI, and device-lane execution.
All wrappers delegate to the config-driven orchestrator:

```bash
python3 tools/devctl/main.py lane <lane> ...
```

## Standard (CI + Local)

```bash
bash scripts/dev/test.sh
```

Modes:

- `bash scripts/dev/test.sh` -> clean + module tests (plus Android unit tests when SDK is configured)
- `bash scripts/dev/test.sh quick` -> test only (no clean)
- `bash scripts/dev/test.sh ci` -> CI-safe alias

Compatibility wrapper:

- `bash scripts/dev/verify.sh` (delegates to `test.sh ci`)

## Physical Device Lane

```bash
bash scripts/dev/device-test.sh
```

Defaults:

1. Runs stage checks and captures baseline.
2. Applies benchmark-friendly device settings.
3. Executes a 10-loop run on `:apps:mobile-android-host:run`.
4. Runs optional framework lanes (`espresso` + `maestro` by default).
5. Restores device settings on exit.

Examples:

- `bash scripts/dev/device-test.sh` (10 runs, default command)
- `bash scripts/dev/device-test.sh 5 smoke-loop` (5 runs, custom label)
- `bash scripts/dev/device-test.sh 10 scenario-a -- ./gradlew --no-daemon :apps:mobile-android-host:run`
- `bash scripts/dev/device-test.sh 10 scenario-a --framework espresso`
- `bash scripts/dev/device-test.sh 10 scenario-a --framework maestro`

## Stage-2 Benchmark Wrapper

```bash
bash scripts/dev/bench.sh stage2 --device <device-id>
```

Contract outputs under `scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/`:

1. `scenario-a.csv`
2. `scenario-b.csv`
3. `stage-2-threshold-input.csv`
4. `threshold-report.txt`
5. `logcat.txt`
6. `notes.md`
7. `summary.json`

## Framework Lanes

Run directly through `devctl`:

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
```

Maestro install (validated against `v1.39.13`):

```bash
curl -Ls https://get.maestro.mobile.dev | bash
```

## Governance Helpers

```bash
bash scripts/dev/docs-drift-check.sh
bash scripts/dev/evidence-check.sh docs/operations/evidence/wp-xx/YYYY-MM-DD-note.md
bash scripts/dev/evidence-check-changed.sh
bash scripts/dev/governance-self-test.sh
```
