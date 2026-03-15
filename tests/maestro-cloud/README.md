# Maestro Cloud Benchmarks

Android cloud coverage here is for hosted emulator/API checks, not physical Samsung GPU qualification.

These flows are intentionally separate from `tests/maestro/`.

Purpose:

1. Keep regular lane/CI Maestro coverage short and deterministic.
2. Hold cloud-only benchmark/qualification flows that are useful for hosted-device performance checks.

Current flow set:

1. `scenario-model-management-split-smoke.yaml` (`cloud-smoke`, `model-management`): clean-install contract for blocked/library entry, advanced entry points, library-only controls, runtime-only controls, and runtime empty state.
2. `scenario-session-drawer-smoke.yaml` (`cloud-smoke`, `sessions`): clean-install session-shell contract using the `session_drawer_button` resource-id selector.
3. `scenario-runtime-ready-smoke.yaml` (`cloud-smoke`, `runtime-readiness`): clean-install readiness contract that ends once the runtime is ready.
4. `scenario-send-after-ready-smoke.yaml` (`cloud-smoke`, `send`): clean-install readiness bootstrap followed by one send assertion using resource-id selectors for composer/send controls.
5. `scenario-gpu-cpu-benchmark.yaml` (`cloud-benchmark`, `benchmark`, `long-running`): clean install, first-run provisioning, GPU-on send benchmark, new-session GPU-off send benchmark, and assertion that GPU completes faster than CPU on the same cloud device.

Recommended command:

```bash
bash scripts/dev/maestro-cloud-smoke.sh
bash scripts/dev/maestro-cloud-gpu-benchmark.sh
```

Cloud suite rules:

1. Keep `cloud-smoke` flows under two minutes and focused on deterministic UI contracts.
2. Keep benchmark/qualification journeys out of smoke by tagging them separately and running them only from dedicated scripts/jobs.
3. Prefer stable selectors, but validate them on hosted devices before broad rollout. The current Pocket GPT Android build now exposes selected Compose `testTag` values as Android resource IDs, so hosted smoke flows should prefer `id:` selectors for stable controls that provide them.
4. Do not duplicate broad local-lane coverage here unless Cloud is adding distinct value, such as hosted-device variance or a clean-install contract.
