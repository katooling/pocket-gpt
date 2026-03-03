# Android DX and Test Playbook

This playbook defines the development and testing workflow for Android-first MVP execution.

## Tooling Stack

1. Android Studio (run/debug, profiler, logcat)
2. ADB (install, shell, log collection)
3. Perfetto (CPU/memory/thermal traces)
4. LeakCanary (memory leak detection)
5. Macrobenchmark (startup and interaction timing)
6. StrictMode (threading/disk/network policy checks)

## Environment Prerequisites

1. JDK 17+
2. Gradle (or Gradle wrapper once added)
3. Android SDK platform-tools (`adb`)
4. One physical Android device with USB debugging enabled

## Fast Local Dev Loop

1. Run module/unit tests for routing, tools, memory logic.
2. Run stage runner flow and capture structured logs by module tag:
   - `Inference`
   - `Routing`
   - `Policy`
   - `Tool`
   - `Memory`
3. Validate diagnostics export for every run.

## Android Device Validation Loop

1. Connect device via USB (developer mode + USB debugging enabled).
2. Install app and clear old data before benchmark runs.
3. Run benchmark scenarios:
   - Scenario A (short text)
   - Scenario B (long text)
   - Scenario C (image)
4. Capture:
   - first-token latency
   - throughput
   - memory
   - thermal notes
   - battery drop over 10 minutes
5. Aggregate with benchmark scripts and compare against thresholds.

## Regression Rules

Fail stage if any occurs:

1. first-token latency regression beyond target band
2. new OOM or ANR in repeated runs
3. tool validation bypass or unsafe payload execution
4. policy allows network in offline-only mode

## Recommended Test Cases Per Stage

### Stage 1

- 10 short chat runs with smoke model
- startup checks must pass

### Stage 2

- scenario A/B with real Qwen 0.8B Q4
- threshold report must pass

### Stage 3

- low battery (<20%) should force downgrade
- high thermal should force downgrade

### Stage 4

- valid tool calls return expected output
- malformed tool payload is rejected

### Stage 5

- relevant memory is retrieved on follow-up prompts
- image input flow returns non-empty analysis

### Stage 6

- 30-minute soak without crash
- diagnostics export is available post-run

## Artifacts to Store

1. benchmark CSV files in `scripts/benchmarks`
2. threshold report output
3. logcat traces per stage
4. updated go/no-go packet
