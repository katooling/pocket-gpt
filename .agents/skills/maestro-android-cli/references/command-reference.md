# Maestro Android CLI Reference

## Device-Pinned Lanes

Use the wrapper when you want repo lanes plus an explicit target device:

```bash
maestro-android lane maestro --device your-device-id
maestro-android lane journey --device your-device-id
maestro-android lane screenshot-pack --device your-device-id
```

This saves the manual `ANDROID_SERIAL=<serial> ...` prefix for delegated lanes.

## Targeted Instrumented Runs

Use `scoped` instead of hand-writing long `connectedDebugAndroidTest` commands:

```bash
maestro-android scoped \
  --type instrumented \
  --device your-device-id \
  --test-class com.pocketagent.android.ChatQuickLoadFlowInstrumentationTest
```

Method-level selection works too:

```bash
maestro-android scoped \
  --type instrumented \
  --device your-device-id \
  --test-class com.pocketagent.android.RealRuntimeJourneyInstrumentationTest#send_flow_reaches_ready
```

Pass runner args without spelling out Gradle properties:

```bash
maestro-android scoped \
  --type instrumented \
  --device your-device-id \
  --test-class com.pocketagent.android.MainActivityUiSmokeTest \
  --runner-arg screenshot_pack_dir=tmp/screenshots \
  --runner-arg screenshot_pack_fallback_dir=tmp/screenshots-fallback \
  --no-build \
  --no-install
```

If you bypass the wrapper, the equivalent direct form is:

```bash
ANDROID_SERIAL=your-device-id ./gradlew --no-daemon \
  :apps:mobile-android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketagent.android.MainActivityUiSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.screenshot_pack_dir=tmp/screenshots
```

## Scoped Maestro Repros

```bash
maestro-android scoped --flow tmp/runtime-ready-repro.yaml
maestro-android scoped --flow tmp/runtime-ready-repro.yaml --no-build --no-install
maestro-android scoped --flow tmp/runtime-ready-repro.yaml -- --debug-output /tmp/maestro-debug
```

Keep scoped flows in `tmp/` and include title/description comments on the first two lines.

## Device Storage Inspection

PocketGPT commonly needs both storage roots:

```bash
# App-private files
maestro-android device files models/

# Shared app-owned media cache (persistent models/downloads)
maestro-android device files --storage media models/
maestro-android device files --storage media runtime-model-downloads/
```

Push a companion artifact to the shared cache:

```bash
maestro-android device push --storage media mmproj-q8_0.gguf models/
```

## Runtime Triage

```bash
maestro-android device logcat --follow --filter "MULTIMODAL|SendMessage|PocketLlama|RuntimeOrchestrator"
maestro-android device logcat --filter "FATAL|SIGSEGV|ANR" --lines 80
maestro-android device ui
maestro-android device info
maestro-android report latest
maestro-android trace latest
```
