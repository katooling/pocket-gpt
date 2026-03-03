# Benchmark Protocol (Phase 0)

## Goal

Produce repeatable feasibility evidence for local text + image inference on representative iOS and Android devices.

## Model Set

1. `Qwen3.5-0.8B` quantized (Q4 baseline)
2. `Qwen3.5-2B` quantized (Q4 baseline)

## Runtime Set

1. Baseline runtime: `llama.cpp` adapter path
2. Optional comparative tracks:
   - iOS: Core ML adapter
   - Android: LiteRT/NNAPI adapter

## Scenarios

### Scenario A: Text Chat (Short)

- prompt length: ~150 input tokens
- output target: 128 tokens
- run count: 10

### Scenario B: Text Chat (Long)

- prompt length: ~1,500 input tokens
- output target: 256 tokens
- run count: 10

### Scenario C: Image Q&A

- one static image/document photo
- fixed prompt template
- output target: 128 tokens
- run count: 10

## Metrics to Capture

1. Cold start time (s)
2. First-token latency (ms)
3. Decode throughput (tok/s)
4. Peak RSS memory (MB)
5. Thermal state trend over 10 minutes
6. Battery drop over fixed 10-minute workload
7. Crash/OOM incidence

## Acceptance Thresholds (MVP Entry)

Mid-tier target devices must satisfy:

1. P50 first-token latency <= 2,500 ms for Scenario A
2. P50 decode throughput >= 8 tok/s for `0.8B`, >= 4 tok/s for `2B`
3. No OOM in benchmark suite
4. Thermal profile does not force severe throttling before 5 minutes
5. Battery drop <= 8% for a 10-minute mixed workload

## Procedure

1. Ensure device is charged > 70% and cooled to baseline.
2. Close background-heavy apps.
3. Run scenarios A/B/C for each model/runtime combination.
4. Record metrics in result template.
5. Repeat once after reboot to validate consistency.

## Output Artifacts

1. `spike-results.md` summary
2. raw logs per device/runtime/model
3. go/no-go recommendation with routing defaults
