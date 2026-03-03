# Android DX and Test Playbook

This playbook defines the development and testing workflow for Android-first MVP execution.

Companion docs:

- `docs/testing/test-strategy.md`
- `docs/roadmap/mvp-implementation-tracker.md`

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

Suggested local command baseline (add wrapper/tasks as repo evolves):

```bash
# one-command clean build + tests (WP-01 baseline)
bash scripts/dev/verify.sh

# optional stage smoke run
./gradlew :apps:mobile-android:run
```

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

## Stage-by-Stage DX + Reliability Gates

### Stage 1

- Keep startup-to-first-response loop under 60s for local iteration.
- Require at least one deterministic unit test for each changed class.
- Keep smoke script output stable and documented.

### Stage 2

- Artifact checksum validation must be covered by unit tests.
- Threshold evaluation script run is required for every benchmark update.
- Benchmark CSV schema changes require script compatibility checks.

### Stage 3

- Routing decisions must be table-tested across battery/thermal/RAM bands.
- Diagnostics export must avoid sensitive content; assert via tests.
- Regression test that downgrade behavior is preserved.

### Stage 4

- Tool validation must reject malformed and adversarial payloads.
- Prefer schema-driven parser/validator over string parsing.
- Tool execution branches require success and failure-path tests.

### Stage 5

- Memory retrieval quality tests required for common follow-up flows.
- Retention/pruning behavior requires deterministic tests.
- Image path must include non-empty output and latency capture assertions.

### Stage 6

- Soak tests produce reproducible artifacts (logs + benchmark + diagnostics).
- Recovery/startup behavior must be tested after forced-stop conditions.
- Release candidate requires all prior stage gates to remain green.

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

1. Benchmark CSV files in `scripts/benchmarks/runs/YYYY-MM-DD/<device>/`
2. Threshold report as `threshold-report.txt` in same run folder
3. Logcat traces as `logcat.txt` in same run folder
4. Optional perf trace as `perfetto.trace` in same run folder
5. Updated go/no-go packet in `docs/roadmap/mvp-beta-go-no-go-packet.md`
