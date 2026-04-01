# Engineer Note: Current Harness Audit

## Topic

Current Pocket GPT benchmark and validation harness behavior.

## Current State

The canonical benchmark entrypoint is:

- `bash scripts/dev/bench.sh stage2 ...`

That wrapper delegates to:

- `python3 tools/devctl/main.py lane stage2`
- `tools/devctl/lanes.py`
- `bash scripts/android/run_stage2_native.sh`
- `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/NativeStage2BenchmarkInstrumentationTest.kt`

The stage-2 instrumentation currently proves these properties:

1. The benchmark run uses `NATIVE_JNI` and fails if the backend is not native JNI.
2. The selected model ID must match the requested model ID, which guards against fallback masking.
3. The run captures median `first_token_ms`, `decode_tps`, token count, and PSS.
4. The harness can warm the model before measured runs, and it records warm-vs-cold first-token delta.
5. `devctl lane stage2` generates a structured run directory with threshold evaluation, runtime-evidence validation, summary JSON, notes, and evidence draft generation.

Relevant code/doc surfaces:

- `scripts/dev/README.md`
- `scripts/benchmarks/README.md`
- `tools/devctl/lanes.py`
- `config/devctl/stage2.yaml`
- `scripts/android/run_stage2_native.sh`
- `scripts/benchmarks/evaluate_thresholds.py`
- `scripts/benchmarks/validate_stage2_runtime_evidence.py`
- `tools/devctl/generate_stage2_evidence_draft.py`
- `docs/testing/runtime-tuning-debugging.md`

The journey lane also contributes runtime evidence outside stage-2:

- `python3 tools/devctl/main.py lane journey`
- `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/RealRuntimeJourneyInstrumentationTest.kt`
- `tools/devctl/lanes.py` writes `journey-report.json`, `journey-summary.md`, and per-step `*-runtime-log-signals.{json,md}` artifacts.

## Evidence

### Stage-2 Artifact Contract

`config/devctl/stage2.yaml` currently expects closure runs to emit:

- `scenario-a.csv`
- `scenario-b.csv`
- `stage-2-threshold-input.csv`
- `model-2b-metrics.csv`
- per-model/per-scenario meminfo snapshots
- `threshold-report.txt`
- `runtime-evidence-validation.txt`
- `runtime-log-signals.json`
- `runtime-log-signals.md`
- `logcat.txt`
- `notes.md`
- `summary.json`

### Default Benchmark Parameters

`scripts/android/run_stage2_native.sh` defaults to:

- `quick`: `runs=2`, `max_tokens_a=4`, `max_tokens_b=4`, `min_tokens=1`, `warmup_max_tokens=0`
- `closure`: `runs=3`, `max_tokens_a=128`, `max_tokens_b=256`, `min_tokens=16`, `warmup_max_tokens=8`

But those values are still overridable through CLI flags or environment variables:

- `POCKETGPT_STAGE2_MAX_TOKENS_A`
- `POCKETGPT_STAGE2_MAX_TOKENS_B`
- `POCKETGPT_STAGE2_MIN_TOKENS`
- `POCKETGPT_STAGE2_WARMUP_MAX_TOKENS`

### What The Instrumentation Actually Measures

`NativeStage2BenchmarkInstrumentationTest`:

1. warms the model once when enabled;
2. runs scenario A or B multiple times;
3. collects streamed tokens and derives `decode_tps` from observed token count and decode duration;
4. asserts the backend is native JNI and the returned model ID matches the requested model.

### Concrete Historical Run Sample

The stored run at:

- `scripts/benchmarks/runs/2026-03-05/RR8NB087YTF/`

shows:

1. `runtime-evidence-validation.txt` passed.
2. `threshold-report.txt` failed because first-token latency was extremely high.
3. `notes.md` reports `Max tokens A/B: 2/2`, despite closure defaults being far higher.
4. `scenario-a.csv` and `model-2b-metrics.csv` report `decode_tps=1000.0000`, which is only plausible because the run used tiny token budgets.

This proves the harness is real, but it also proves the metric contract is vulnerable to low-token overrides that make decode throughput comparisons meaningless.

## Risk Or Gap

1. Stage-2 only benchmarks scenario `A` and `B`; it does not measure image, tool, or multi-session cache-heavy paths.
2. The CSV outputs store medians only, not raw per-run samples, so variance and flakiness are obscured.
3. The validator checks for file presence, `NATIVE_JNI`, and positive metrics, but it does not reject obviously weak benchmark setups such as ultra-low token counts.
4. Threshold evaluation only looks at median `first_token_ms` and `decode_tps`; it does not consider sample size, variance, thermal behavior, or whether the token budget was large enough to make decode metrics meaningful.
5. The historical closure run demonstrates contract drift: the run directory lacks `runtime-log-signals.{json,md}` even though current config expects them.

## Recommendation

1. Make closure benchmarks non-overridable for minimum token budget, or at least reject closure runs below a hard floor.
2. Emit raw per-run rows in addition to medians, including:
   - run index
   - token count
   - requested max tokens
   - warmup enabled
   - cold/warm flag
3. Extend the runtime evidence validator to assert:
   - realistic token budget for closure
   - presence of runtime-log-signal artifacts
   - threshold pass for closure, not just file integrity
4. Add benchmark lanes for optimization-sensitive paths that stage-2 currently misses:
   - session switch-back / prefix-cache reuse
   - speculative on/off comparison
   - image path
   - tool loop path

## Validation Needed

1. Re-run stage-2 closure with locked defaults and confirm the validator rejects underpowered benchmark settings.
2. Confirm raw per-run outputs can explain variance across repeated runs.
3. Verify the historical artifact contract and current config are aligned.

## Open Questions

1. Should closure profile forbid any `max_tokens` or `min_tokens` overrides entirely?
2. Should stage-2 remain median-only for release notes while storing raw rows for engineering analysis?
3. Should journey artifacts be promoted into the same closure packet as stage-2 for runtime tuning changes?
