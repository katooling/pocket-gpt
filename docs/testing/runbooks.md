# Testing Runbooks

Last updated: 2026-03-08

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

## Runbook: Android UI/Runtime Smoke

```bash
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
```

## Runbook: Send/Runtime Journey Validation

```bash
python3 tools/devctl/main.py lane journey --repeats 1 --mode strict --reply-timeout-seconds 90
```

Use `--mode valid-output` for slower devices where terminal output validation is required over SLA-oriented strictness.

## Runbook: Screenshot Contract Validation

```bash
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py governance screenshot-inventory-check
```

## Runbook: Stage-2 Runtime Closure

```bash
bash scripts/dev/bench.sh stage2 --profile closure --device <device-id> --models both --scenarios both --install-mode auto
```

Attach evidence note and run:

```bash
python3 tools/devctl/main.py governance evidence-check docs/operations/evidence/wp-xx/YYYY-MM-DD-note.md
```
