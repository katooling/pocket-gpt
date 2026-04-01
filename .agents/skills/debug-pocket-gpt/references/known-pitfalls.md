# Known Pitfalls and Documented Issues

A living reference of issues encountered during PocketGPT debugging on physical devices.
Organized by category. Each entry includes symptoms, root cause, and resolution.
Add new entries as they're discovered.

---

## Inference Issues

### Context Window Budget Exhaustion

**Symptoms**: Model produces random numbers, symbols, or incoherent fragments
consistently (not intermittently). Looks like the model has zero understanding of the prompt.

**Root cause**: `maxTokens` (generation budget) consumes nearly the entire context window,
leaving almost no room for the prompt. The budget formula in `pocket_llama.cpp`:
`max_prompt_tokens = max(1, n_ctx - safe_max_tokens - 1)`. When maxTokens >= n_ctx,
the prompt gets trimmed to as few as 1 token.

**Resolution**: Clamp maxTokens to at most half the context window in
`ChatRuntimeService.kt:resolveMaxTokens()`. Lower the UI default in `ChatUiState.kt`.

**Detection**: Check `DIAG_TOKENS` log — if `max_prompt` is much smaller than `total`,
this is the issue.

### Qwen3 Thinking Mode Overhead

**Symptoms**: Model produces long `<think>...</think>` blocks despite `/no_think` in
system prompt. Generation much slower than expected.

**Root cause**: The `/no_think` text directive doesn't reliably suppress thinking in
Qwen3 models. The official mechanism requires pre-filling `<think></think>` in the
assistant response prefix.

**Workaround**: Accept thinking overhead or use a non-thinking model (Phi-4, SmolLM3)
for debugging when you need fast responses.

---

## Persistence Issues

### Stale SharedPreferences

**Symptoms**: Code changes to profiles, KV cache types, or GPU settings don't take
effect on device. Logs show old parameter values.

**Root cause**: `RuntimeTuningStore` persists runtime parameters to SharedPreferences.
Old persisted values take precedence over new code defaults on startup.

**Resolution**:
```bash
adb -s <device-id> shell run-as com.pocketagent.android \
  rm -f shared_prefs/pocketagent_runtime_tuning.xml
adb -s <device-id> shell am force-stop com.pocketagent.android
```

For production: Add migration guards (e.g., `safeKvCacheType()`) that reject invalid
or outdated persisted values.

---

## Device Issues

### Thermal Throttling

**Symptoms**: Generation starts but times out. Thread is in `R (running)` state but
producing tokens extremely slowly (0.5 tok/s vs normal 5-10 tok/s). Device is hot.

**Root cause**: Sustained CPU inference heats the SoC, dropping clock speed 5-50x.

**Mitigation**:
1. Let device cool 10-15 minutes between intensive runs
2. Force-stop app between tests
3. Use a more powerful device for fast iteration
4. Reduce prompt size if possible

### Logcat Buffer Rotation

**Symptoms**: Diagnostic logs vanish between when they're written and when you read them.
Earlier logs present but later ones (like sampled tokens) are missing.

**Root cause**: Android logcat is a ring buffer. Budget devices have ~256KB buffers.
Long inference runs produce enough logs to rotate out your diagnostic output.

**Resolution**: Always stream logcat to a file BEFORE starting the test:
```bash
adb -s <device-id> logcat -s PocketLlamaJNI:I > /tmp/diag.log &
```

---

## Test Infrastructure Issues

### Maestro clearState Wiping Models

**Symptoms**: Maestro flow starts but runtime never reaches "Ready" state. Previously
downloaded model is gone.

**Root cause**: `clearState: true` in bootstrap flows wipes ALL app data including
downloaded GGUF model files. Without a model, the runtime can't load.

**Resolution**: Use `clearState: false` for debug flows. If you need clean config state
without losing models, clear only SharedPreferences:
```bash
adb shell run-as com.pocketagent.android rm -f shared_prefs/*.xml
```

### Maestro Relative Path Resolution

**Symptoms**: Maestro flow fails to find a referenced sub-flow file.

**Root cause**: Maestro resolves `runFlow:` paths relative to the flow file's directory,
NOT the current working directory. A flow in `tmp/my-flow.yaml` referencing
`tests/maestro/shared/bootstrap.yaml` needs `../tests/maestro/shared/bootstrap.yaml`.

**Resolution**: Use paths relative to the flow file location, or use absolute paths.

---

## ADB Interaction Issues

### Keyboard Coordinate Shift

**Symptoms**: ADB tap commands miss their target after the soft keyboard appears.
Send button tap doesn't register.

**Root cause**: Keyboard pushes the app layout upward. A button at y=2248 before
keyboard might be at y=1347 after. Coordinates from a pre-keyboard UI dump are stale.

**Resolution**: Always re-dump the UI hierarchy AFTER the keyboard appears, then
extract fresh coordinates.

### Text Input Encoding

**Symptoms**: `adb shell input text "Say hi"` produces garbled input or unexpected
characters on the device.

**Root cause**: `input text` doesn't handle spaces. The `%s` encoding for spaces
is unreliable across Android versions and can produce newlines.

**Resolution**: Use single words (`Hello`, `Hi`) for test messages. For multi-word
input, use `%s` but verify the result before sending.

### Input Appending

**Symptoms**: The composer field shows concatenated text from multiple input attempts.

**Root cause**: Repeated `input text` calls append to existing content rather than
replacing it. If previous text wasn't sent or cleared, it accumulates.

**Resolution**: Force-stop and restart the app for a clean composer:
```bash
adb shell am force-stop com.pocketagent.android
adb shell am start -n com.pocketagent.android/.MainActivity
```

---

## Methodology Anti-Patterns

### Guess-and-Check Debugging

**Symptoms**: Changing one parameter, rebuilding, testing, learning nothing, changing
another parameter, repeat. Hours of wasted time.

**Root cause**: Debugging without formulating theories or defining what evidence would
confirm/deny each hypothesis.

**Resolution**: Write down 3-7 theories with falsification criteria. Add diagnostic
logging at all relevant stages in one build. Deploy once, analyze data, then fix.

### One Log Point Per Build

**Symptoms**: Each investigation takes N deploys for N log points. Minutes of build
time per deploy. Entire day spent on what should take an hour.

**Root cause**: Adding diagnostic logging incrementally — one log point, deploy, check,
add another, deploy again.

**Resolution**: Add ALL diagnostic log points in a single build. The goal is to capture
the full pipeline in one test run. Read the data, then decide what to change.
