# Maestro Android CLI Reference

## Device-Pinned Lanes

```bash
maestro-android lane maestro --device RFCT2178PDV
maestro-android lane journey --device RFCT2178PDV
maestro-android lane screenshot-pack --device RFCT2178PDV
```

## Targeted Instrumented Runs

```bash
maestro-android scoped \
  --type instrumented \
  --device RFCT2178PDV \
  --test-class com.pocketagent.android.ChatQuickLoadFlowInstrumentationTest
```

```bash
maestro-android scoped \
  --type instrumented \
  --device RFCT2178PDV \
  --test-class com.pocketagent.android.MainActivityUiSmokeTest \
  --runner-arg screenshot_pack_dir=tmp/screenshots \
  --runner-arg screenshot_pack_fallback_dir=tmp/screenshots-fallback \
  --no-build \
  --no-install
```

## Scoped Maestro Repros

```bash
maestro-android scoped --flow tmp/runtime-ready-repro.yaml
maestro-android scoped --flow tmp/runtime-ready-repro.yaml --no-build --no-install
```

## Device Storage Inspection

```bash
maestro-android device files models/
maestro-android device files --storage media models/
maestro-android device files --storage media runtime-model-downloads/
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
