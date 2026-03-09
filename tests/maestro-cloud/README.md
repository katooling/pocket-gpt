# Maestro Cloud Benchmarks

These flows are intentionally separate from `tests/maestro/`.

Purpose:

1. Keep regular lane/CI Maestro coverage short and deterministic.
2. Hold cloud-only benchmark/qualification flows that are useful for hosted-device performance checks.

Current flow set:

1. `scenario-gpu-cpu-benchmark.yaml`: clean install, first-run provisioning, GPU-on send benchmark, new-session GPU-off send benchmark, and assertion that GPU completes faster than CPU on the same cloud device.

Recommended command:

```bash
bash scripts/dev/maestro-cloud-gpu-benchmark.sh
```
