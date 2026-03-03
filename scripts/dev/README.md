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

## Standard (CI + Local)

```bash
bash scripts/dev/test.sh [full|quick|ci]
```

Modes:

- `full` (default): clean + module tests (plus Android unit tests when SDK is configured)
- `quick`: no clean
- `ci`: CI-safe alias

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
bash scripts/dev/bench.sh stage2 --device <device-id> [--date YYYY-MM-DD] [--scenario-a <file>] [--scenario-b <file>]
```

Contract outputs under `scripts/benchmarks/runs/YYYY-MM-DD/<device-id>/`:

1. `scenario-a.csv`
2. `scenario-b.csv`
3. `stage-2-threshold-input.csv`
4. `threshold-report.txt`
5. `logcat.txt`
6. `notes.md`
7. `summary.json`

## Framework Lanes (Direct)

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
```

Maestro install:

```bash
curl -Ls https://get.maestro.mobile.dev | bash
```

## Governance Commands

Wrappers remain callable, but all governance logic runs via `devctl governance`.

```bash
python3 tools/devctl/main.py governance docs-drift
python3 tools/devctl/main.py governance evidence-check docs/operations/evidence/wp-xx/YYYY-MM-DD-note.md
python3 tools/devctl/main.py governance evidence-check-changed
python3 tools/devctl/main.py governance validate-pr-body /tmp/pr-body.md
python3 tools/devctl/main.py governance stage-close-gate /tmp/pr-body.md
python3 tools/devctl/main.py governance self-test
```
