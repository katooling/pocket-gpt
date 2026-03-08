# Docs Governance

Last updated: 2026-03-08

Docs governance has three layers:

1. `docs-drift`: checks canonical-vs-noncanonical command/process duplication policy.
2. `docs-health`: checks link integrity, status-field policy, required indexes, and evidence-retention policy from config.
3. `docs-accuracy`: checks feature-doc-code parity using `docs/governance/docs-accuracy-manifest.json` and emits a machine-readable drift report.

## Contracts

- Policy config: `config/devctl/docs-governance.json`
- Feature parity manifest: `docs/governance/docs-accuracy-manifest.json`
- Drift report output: `build/devctl/docs-drift-report.json`

## Commands

```bash
python3 tools/devctl/main.py governance docs-drift
python3 tools/devctl/main.py governance docs-health
python3 tools/devctl/main.py governance docs-accuracy
python3 tools/devctl/main.py governance screenshot-inventory-check
```

## Drift Report Schema (v1)

`build/devctl/docs-drift-report.json` contains:

1. `schema`
2. `generated_at_utc`
3. `status`
4. `checks[]`
5. `violations[]`

Use this file in CI annotations or release-readiness summaries.
