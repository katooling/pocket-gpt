# New Joiner Guide

Use this path to get productive in 10 minutes.

## 1) Understand the project shape

1. Read `README.md`.
2. Read `docs/start-here/resource-map.md`.
3. Open `docs/operations/execution-board.md` for current priorities.

## 2) Verify your local environment

```bash
python3 -m pip install -r tools/devctl/requirements.txt
python3 tools/devctl/main.py doctor
```

If `doctor` fails, fix issues first.

## 3) Run the confidence baseline (4 commands)

```bash
python3 tools/devctl/main.py doctor
bash scripts/dev/test.sh quick
python3 tools/devctl/main.py lane android-instrumented
python3 tools/devctl/main.py lane maestro
```

For UI-focused changes, also run:

```bash
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py governance screenshot-inventory-check
```

## 4) Find your role lane

- Engineering: `docs/operations/role-playbooks/engineering-playbook.md`
- Product: `docs/operations/role-playbooks/product-playbook.md`
- QA: `docs/operations/role-playbooks/qa-playbook.md`
- Marketing: `docs/operations/role-playbooks/marketing-playbook.md`

## 5) Work contract

1. `docs/operations/execution-board.md` is the only mutable status board.
2. Ticket specs live under `docs/operations/tickets/`.
3. Keep command/process details in canonical docs only:
   - `scripts/dev/README.md`
   - `docs/testing/test-strategy.md`
   - `docs/testing/android-dx-and-test-playbook.md`
4. Evidence notes live under `docs/operations/evidence/` and follow retention policy in `docs/operations/evidence/index.md`.
