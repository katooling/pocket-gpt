---
name: debugging-pocket-gpt
description: Guides systematic debugging of PocketGPT on physical Android devices. Covers building, deploying, logcat capture, UI dumps, theory falsification across all app layers (Compose UI, Kotlin runtime, JNI bridge, C++ inference), and common pitfalls. Triggers on debug, crash, device issue, app not working, fix on phone, test on device, garbled output, timeout, model won't load, or unexpected behavior.
---

# Debugging PocketGPT on Physical Devices

## Method

**Diagnose before prescribing.** PocketGPT spans Kotlin UI → KMP runtime → JNI bridge → C++ inference. A symptom in one layer often originates in another.

1. **Reproduce** — confirm on device, capture evidence
2. **Theorize** — list competing explanations
3. **Falsify** — define what log output would PROVE each theory wrong
4. **Instrument** — add targeted logging at ALL relevant stages in ONE build
5. **Observe** — deploy once, test once, read the data
6. **Eliminate** — cross off contradicted theories
7. **Fix and verify** — minimal change, confirm via same diagnostic data

## Architecture Quick Reference

```
UI (Compose)  →  Runtime (KMP)  →  JNI Bridge  →  llama.cpp (C++)
```

| Layer | Key files |
|---|---|
| UI | `apps/mobile-android/.../ui/state/ChatUiState.kt`, `.../ui/controllers/ChatSendFlow.kt` |
| Runtime | `packages/app-runtime/.../runtime/ChatRuntimeService.kt`, `PerformanceProfiles.kt` |
| Persistence | `apps/mobile-android/.../runtime/RuntimeTuningStore.kt` |
| Native bridge | `apps/mobile-android/src/main/cpp/pocket_llama.cpp` |

## Build and Deploy

```bash
bash scripts/android/dev_loop.sh --serial <device-id>   # full cycle
./gradlew :apps:mobile-android:installDebug              # just install
adb -s <id> shell am start -n com.pocketagent.android/.MainActivity
```

### Choosing the Right Tool

| Situation | Command |
|---|---|
| Iterating on a code change | `dev_loop.sh --serial <id>` |
| Reproducing a crash/hang | `maestro-android scoped --flow tmp/repro.yaml` |
| Quick unit test | `bash scripts/dev/test.sh fast` |
| Full E2E before merge | `devctl lane journey` |
| UI smoke test | `maestro-android lane smoke` |
| Fast re-run without rebuild | `maestro-android scoped --flow <yaml> --no-build --no-install` |

## Capturing Evidence

### Logcat (Primary)

```bash
adb -s <id> logcat -c
adb -s <id> logcat -s PocketLlamaJNI:I > /tmp/diag.log 2>&1 &
# ... reproduce ...
kill %1 2>/dev/null
```

| Tag | Covers |
|---|---|
| `PocketLlamaJNI` | Native bridge: inference, sampling, GPU, cache |
| `PocketLlamaCore` | llama.cpp internals: graph allocation, memory |
| `etagent.android` | App lifecycle, GC |

### UI State

```bash
adb -s <id> shell uiautomator dump /data/local/tmp/ui.xml
adb -s <id> shell cat /data/local/tmp/ui.xml | sed 's/></>\n</g' | grep 'text="' | grep -v 'text=""'
```

## Debugging by Symptom

### Garbled Output
**Layers**: Runtime (context budget), Native bridge (tokenization)
1. Check `maxTokens` vs `n_ctx`
2. Check special token recognition
3. Check sampling temperature
4. See `references/inference-debugging.md` for 5 diagnostic log points

### App Crash During Generation
**Layers**: Native bridge (segfault, OOM), GPU offload
1. Check logcat for `SIGSEGV`, `SIGABRT`, `fatal`
2. Check `GPU_OFFLOAD|` logs
3. Use `maestro-android scoped` for structured repro
4. Check `adb shell dumpsys meminfo com.pocketagent.android`

### No Response (Timeout)
**Layers**: Device (thermal), Native bridge (stuck decode)
1. Check inference thread state — `R` (running) vs `S` (sleeping)
2. If `R`: thermal throttle — let device cool
3. If `S`: blocked on lock/IO — check logcat
4. Check if prompt decode started via `PROMPT_DECODE|progress`

### Settings Not Taking Effect
**Layer**: Persistence (stale SharedPreferences)
1. `adb shell run-as com.pocketagent.android rm -f shared_prefs/pocketagent_runtime_tuning.xml`
2. Force-stop and restart

### Model Won't Load
**Layers**: Persistence (files missing), Provisioning
1. `adb shell ls -la /sdcard/Android/media/com.pocketagent.android/`
2. Check storage: `adb shell df /sdcard`
3. Check logcat for provisioning errors

## Adding Diagnostic Logging

- Add logs at EVERY relevant stage in ONE build
- Use `DIAG_` prefix for easy grep
- Log inputs AND outputs of each stage
- Include context (IDs, sizes, flags) — not just "reached here"

```kotlin
android.util.Log.i("DIAG", "DIAG_PLAN|maxTokens=$maxTokens|nCtx=${config.nCtx}")
```

See `references/inference-debugging.md` for 5 ready-to-use C++ log points.

## Common Pitfalls

| Pitfall | Prevention |
|---|---|
| Logcat buffer rotation | Always stream to file: `logcat > /tmp/file.log &` |
| Stale SharedPreferences | Clear with `run-as ... rm shared_prefs/pocketagent_runtime_tuning.xml` |
| Maestro `clearState: true` wipes models | Use `clearState: false` for debug flows |
| Guess-and-check debugging | Formulate theories + falsification criteria first |
| Single log point per build | Add ALL diagnostic logs in one build |
| Thermal throttle misdiagnosis | Check thread state + CPU temp; let device cool |

## References

- `references/device-interaction.md` — full ADB command reference
- `references/inference-debugging.md` — 5 diagnostic C++ log points
- `references/known-pitfalls.md` — detailed known issues with fixes
