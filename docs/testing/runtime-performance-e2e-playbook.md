# Runtime Performance E2E Playbook

Last updated: 2026-03-27

This is the practical playbook for keeping PocketGPT fast without turning every investigation into a multi-day archaeology exercise.

Use this document when you need to:

1. prove a runtime or latency fix is real;
2. reproduce a performance regression quickly on device;
3. understand which lane to run next;
4. compare PocketGPT against PocketPal on the same device/model;
5. avoid re-learning the same setup and artifact-reading steps.

## What We Stabilized

These are the recent changes that moved the app from "works, but painfully slow" to "usable and measurable."

1. CPU specialization is now real, not cosmetic. The specialized ARM runtime now builds the heavy `ggml` and `llama` cores, not only the wrapper JNI library. This is why high-end ARM phones stopped spending tens of seconds in prompt prefill.
2. Android CPU fallback is now a first-class runtime path. We made `useMmap=false` the safer CPU default, added native mmap timing telemetry, and expanded tuning data so CPU mode can be demoted intelligently instead of failing blindly.
3. Shared-session prefix reuse now works on hybrid/recurrent models like `qwen35`. Those models now use a prefill-only checkpoint strategy instead of unsafe tail-trim reuse.
4. OpenCL is now tightly gated and diagnostic-rich. Unsupported phones stay on CPU instead of crashing or thrashing.

## Canonical Confidence Ladder

Use the smallest standard command that proves the change.

1. `bash scripts/dev/test.sh fast`
   Fast host confidence for touched Kotlin/native/script code.
2. `bash scripts/dev/test.sh merge`
   Broad merge-equivalent contract coverage.
3. `python3 tools/devctl/main.py doctor`
   Environment and lane readiness.
4. `python3 tools/devctl/main.py lane android-instrumented`
   Android runtime and bridge smoke.
5. `python3 tools/devctl/main.py lane maestro`
   Canonical UI smoke.
6. `python3 tools/devctl/main.py lane journey`
   Strict send/runtime evidence.
7. `bash scripts/dev/scoped-repro.sh --flow tmp/<flow>.yaml`
   One bounded device bug path only.
8. `bash scripts/dev/bench.sh stage2 ...`
   Physical-device timing evidence.

## Fastest Useful Runtime Loops

### Shared Prefix-Cache Regression Gate

This is the smallest real-device benchmark we now expect to stay green for the `qwen35` shared-session path.

```bash
bash scripts/dev/prefix-cache-regression.sh --device <serial> --install-mode skip
```

What it proves:

1. the app can load the 0.8B model on device;
2. shared-session follow-up prompting still works;
3. prefix cache actually hits;
4. reused tokens are greater than zero;
5. stage-2 fails loudly if that regression comes back.

### Scoped Runtime Repro

Use this only when a problem is device-specific and fits into one short journey.

```bash
bash scripts/dev/scoped-repro.sh --flow tmp/<flow>.yaml --serial <serial>
```

Add `--no-build --no-install` after the first run when only app state or harness behavior changed.

### PocketPal Parity Loop

When comparing PocketGPT and PocketPal, keep all four variables identical:

1. same phone;
2. same model artifact;
3. same prompt content and max-token budget;
4. same warm/cold state.

The minimum parity set is:

1. cold app launch -> first send;
2. warm model -> first send;
3. second send with same session prefix;
4. offload/reload -> send.

### CPU Sweep Loop

Use this once the prefix-cache regression gate is green and you want to tune CPU fallback on the same phone/model without rebuilding commands by hand.

```bash
bash scripts/dev/cpu-sweep.sh --device <serial> --install-mode skip
```

What it does:

1. runs a curated 0.8B shared-session stage-2 sweep;
2. keeps tools disabled so the sweep reflects runtime costs, not tool-schema overhead;
3. requires prefix-cache hits so tuning does not silently regress shared follow-up behavior;
4. ranks the variants into a single summary report.

Add PocketPal parity when you have a raw export:

```bash
bash scripts/dev/cpu-sweep.sh --device <serial> --install-mode skip --pocketpal tmp/pocketpal-benchmark.json
```

## Artifact Reading Order

When a benchmark finishes, read files in this order:

1. `summary.json`
2. `scenario-a.csv` / `scenario-b.csv`
3. `runtime-log-signals.md`
4. `logcat.txt`
5. `meminfo-*.txt`
6. `evidence-draft.md`

When you only need to answer "why was the send slow?", the highest-value fields are:

1. `model_load_ms`
2. `prefill_ms`
3. `decode_ms`
4. `prefix_cache_hit`
5. `prefix_cache_reused_tokens`
6. `mmap_readahead_ms`
7. `active_backend`
8. `qualification_state`

## Time Savers That Matter

1. Reuse installed APKs with `--install-mode skip` after the first device run.
2. Keep model files in shared/external storage so resets do not force re-import or re-download.
3. Run only the selected model in stage-2. `run_stage2_native.sh` now requires only the model path for the models you actually requested.
4. Use `bash scripts/dev/prefix-cache-regression.sh` instead of rebuilding a long shared-session benchmark command from memory.
5. Use `bash scripts/dev/cpu-sweep.sh` for repeatable CPU tuning instead of one-off stage-2 flag experiments.
6. Prefer `--models 0.8b --scenarios a --profile quick` for iteration, then promote to closure only after the signal is stable.
7. Treat Maestro launch flakes separately from app regressions. If logcat shows no crash/runtime signature and Maestro fails at app launch, classify it as harness noise until disproven.

## What We Want To Build Next

This is the breadth-first candidate list, with spike result and decision.

### 1. CPU Parameter Sweeps

Spike result:

1. After the ARM specialization fix, CPU execution is no longer fundamentally broken.
2. That means remaining wins are now likely in `n_ctx`, batch sizing, thread counts, flash-attention mode, and tool-prompt overhead.

Decision:

1. keep this as the primary next optimization lane;
2. compare every change against PocketPal on the same phone and model;
3. use stage-2, not feel, as the source of truth.

Current state:

1. implemented as a repeatable compact/full sweep through `bash scripts/dev/cpu-sweep.sh`;
2. still needs repeated device runs and PocketPal exports to choose the best long-term defaults.

### 2. Shared Prefix-Cache Regression Coverage

Spike result:

1. prefix reuse was a real end-to-end latency win once the model-aware checkpoint strategy was fixed;
2. the old bug could regress silently without a dedicated shared-session benchmark check.

Decision:

1. turn it into a hard device benchmark gate.

Current state:

1. implemented:
   - `stage2_require_prefix_cache_hit`
   - `bash scripts/dev/prefix-cache-regression.sh --device <serial>`

### 3. Quick-Load / Reload User Flow Coverage

Spike result:

1. runtime fixes are easy to validate in logs and still miss the visible app flow;
2. load-last-used, follow-up send, offload, reload, and send again are all user-visible surfaces that can regress without touching stage-2.

Decision:

1. add a stable instrumentation regression around that full flow.

Current state:

1. implemented in `ChatQuickLoadFlowInstrumentationTest`.

### 4. Diagnostics Visibility

Spike result:

1. we were repeatedly forced back into native logcat to answer basic questions like "is this model hybrid?" and "why is prefix cache behaving differently?"

Decision:

1. surface model memory mode and prefix-cache mode in exported diagnostics and the in-app diagnostics panel.

Current state:

1. implemented.

### 5. OpenCL Recovery

Spike result:

1. current Qualcomm enumeration is still failing at native runtime discovery on the S22-class phone;
2. the CPU path is now good enough that broad GPU policy changes are not the best use of time.

Decision:

1. keep OpenCL frozen except for a narrow native-only spike when explicitly scheduled.

Current state:

1. not selected for the mainline performance lane right now.

### 6. Hexagon Viability

Spike result:

1. Hexagon is still a plausible upside path on supported Qualcomm devices;
2. it is not a small tweak and should not block CPU-path work.

Decision:

1. treat Hexagon as a bounded reference-device spike only.

Current state:

1. not selected for the current mainline lane.

## Current Stable Gate Set

When changing runtime performance code now, run this minimum set before calling the work stable:

1. `bash scripts/dev/test.sh fast`
2. `./gradlew --no-daemon :apps:mobile-android:testDebugUnitTest --tests com.pocketagent.android.runtime.RuntimeDiagnosticsSnapshotParserTest`
3. `ANDROID_SERIAL=<serial> ./gradlew --no-daemon :apps:mobile-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.pocketagent.android.ChatQuickLoadFlowInstrumentationTest`
4. `bash scripts/dev/prefix-cache-regression.sh --device <serial> --install-mode skip`

Use `merge`, `android-instrumented`, `maestro`, or `journey` when the change touches broader app behavior or release-facing risk.
