# Just-CLI Android Validation Policy (Pointer Doc)

Last updated: 2026-03-03

## Source of truth

- Workflow/commands: `scripts/dev/README.md`
- Strategy/release gates: `docs/testing/test-strategy.md`
- Android lane details: `docs/testing/android-dx-and-test-playbook.md`

## Policy Summary

1. Keep CI deterministic and fast with host/JVM + Android unit lanes.
2. Keep physical-device validation in a CLI lane with reproducible artifacts.
3. Require human-in-loop only for USB trust/bootstrap, environment control, and anomaly adjudication.
4. Treat raw benchmark outputs and human evidence as separate concerns:
   - raw: `scripts/benchmarks/runs/...`
   - evidence notes/status: `docs/operations/evidence/...`
