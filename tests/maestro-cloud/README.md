# Maestro Cloud Benchmarks

Android cloud coverage here is for hosted emulator/API checks, not physical Samsung GPU qualification.

These flows are intentionally separate from `tests/maestro/`.

Purpose:

1. Keep regular lane/CI Maestro coverage short and deterministic.
2. Hold cloud-only benchmark/qualification flows that are useful for hosted-device performance checks.

Current flow set:

1. `scenario-model-management-split-smoke.yaml` (`cloud-smoke`, `model-management`): clean-install contract for blocked/library entry, advanced entry points, library-only controls, runtime-only controls, and runtime empty state.
2. `scenario-gpu-cpu-benchmark.yaml` (`cloud-benchmark`, `benchmark`, `long-running`): clean install, first-run provisioning, GPU-on send benchmark, new-session GPU-off send benchmark, and assertion that GPU completes faster than CPU on the same cloud device.

Recommended command:

```bash
bash scripts/dev/maestro-cloud-smoke.sh
bash scripts/dev/maestro-cloud-gpu-benchmark.sh
```

Cloud suite rules:

1. Keep `cloud-smoke` flows under two minutes and focused on deterministic UI contracts.
2. Keep benchmark/qualification journeys out of smoke by tagging them separately and running them only from dedicated scripts/jobs.
3. Prefer stable selectors, but validate them on hosted devices before broad rollout. In the current Pocket GPT Android build, Compose `testTag` values used by instrumentation are not reliably visible to Maestro Cloud, so text selectors remain the stable option there.
4. Do not duplicate broad local-lane coverage here unless Cloud is adding distinct value, such as hosted-device variance or a clean-install contract.
