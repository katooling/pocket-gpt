# Screenshot Regression Workflow

Last updated: 2026-03-06

Source of truth for command syntax: `scripts/dev/README.md`.

## Purpose

Capture a full, repeatable UI screenshot pack on physical device and run manual regression review with a strict required inventory.

## Inventory Contract

- Inventory file: `tests/ui-screenshots/inventory.yaml`
- Schema: `ui-screenshot-inventory-v1`
- Stable IDs + normalized filenames use `ui-xx-<slug>.png`
- Required IDs missing from a run cause lane failure

## Canonical Commands

Capture pack:

```bash
python3 tools/devctl/main.py lane screenshot-pack
```

Promote latest normalized screenshots to reference pack:

```bash
python3 tools/devctl/main.py lane screenshot-pack --update-reference
```

Validate inventory/reference/report contract:

```bash
python3 tools/devctl/main.py governance screenshot-inventory-check
```

## Artifact Locations

Per run output:

- `scripts/benchmarks/runs/YYYY-MM-DD/<serial>/screenshot-pack/<stamp>/instrumented/`
- `scripts/benchmarks/runs/YYYY-MM-DD/<serial>/screenshot-pack/<stamp>/maestro/`
- `scripts/benchmarks/runs/YYYY-MM-DD/<serial>/screenshot-pack/<stamp>/combined/`
- `scripts/benchmarks/runs/YYYY-MM-DD/<serial>/screenshot-pack/<stamp>/inventory-report.json`
- `scripts/benchmarks/runs/YYYY-MM-DD/<serial>/screenshot-pack/<stamp>/inventory-report.md`

Reference pack (in-repo):

- `tests/ui-screenshots/reference/sm-a515f-android13/`
- Gallery index: `tests/ui-screenshots/reference/sm-a515f-android13/index.md`

## Manual Review Procedure

1. Run `lane screenshot-pack`.
2. Open latest `inventory-report.md` and confirm zero missing IDs.
3. Compare `combined/` against reference pack screenshots in `tests/ui-screenshots/reference/sm-a515f-android13/`.
4. Record obvious regressions and disposition in PR notes/evidence note.
5. If new UI is intentional and approved, run `--update-reference` and include that change in the same PR.

## Required Pre-Push Checks (UI-touching changes)

1. `python3 tools/devctl/main.py lane screenshot-pack`
2. Manual screenshot review completed
3. `python3 tools/devctl/main.py governance screenshot-inventory-check`
4. PR template screenshot checkbox checked with notes when exceptions apply
