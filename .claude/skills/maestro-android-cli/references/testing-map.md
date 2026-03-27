# PocketGPT Maestro Android Testing Map

Use the smallest standard command that proves the change.

## Canonical Ladder

1. `bash scripts/dev/test.sh fast`
   - Fast local confidence for changed code.
   - Use first when you are not on-device.
2. `bash scripts/dev/test.sh merge`
   - Merge-equivalent confidence for broader unit/contract coverage.
   - Use before pushing if the change is not device-only.
3. `python3 tools/devctl/main.py doctor`
   - Confirms the local environment and lane prerequisites.
4. `python3 tools/devctl/main.py lane android-instrumented`
   - Validates Android runtime/bridge boot on device or emulator.
5. `python3 tools/devctl/main.py lane maestro`
   - Canonical Maestro UI smoke.
   - Use `--include-tags smoke` or `--include-tags model-management` when you only need one slice.
6. `python3 tools/devctl/main.py lane journey`
   - Use for strict send/runtime journey evidence.
   - Add `--steps instrumentation,send-capture,maestro` only when you explicitly want Maestro replay in the same lane.
7. `python3 tools/devctl/main.py lane screenshot-pack`
   - Use for screenshot inventory and reference-pack validation.
   - Add `--product-signal-only` when harness-noise should be caveated, not blocking.
8. `bash scripts/dev/scoped-repro.sh --flow tmp/<name>.yaml`
   - Use only for one short runtime crash/hang/regression path.
   - Keep the flow minimal, with `title` and `description` comments in the first two lines.
9. `maestro-android cloud smoke`
   - Hosted supplemental smoke coverage only.
10. `maestro-android cloud benchmark`
   - Hosted GPU-vs-CPU benchmark coverage only.
11. `maestro-android cloud status label:upload-id`
   - Poll upload ids when a cloud run has already been started.

## Best Practices

- Keep scoped repros in `tmp/` and promote recurring risks into stable flows under `tests/maestro/`.
- Use report helpers before digging through raw artifact folders.
- Prefer stable selectors (`id:`/resource-id) over fragile text when the app exposes them.
- Treat cloud coverage as supplemental. Do not use it as the only merge gate.
- Preserve first-failure artifacts when a bounded retry is part of the gate.
