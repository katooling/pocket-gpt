# GPU Qualification Split Plan

## Purpose

Use the cheapest environment for each question instead of trying to force one platform to prove everything.

## What Each Lane Proves

### Maestro Cloud Android

Use Maestro Cloud only for Android UI and API-tier coverage:

1. Model provisioning flow still works.
2. GPU qualification state leaves `Validating GPU support...`.
3. Eligible builds show `Enable GPU acceleration`.
4. Ineligible builds show the correct disabled reason.
5. The behavior is stable across Android API levels.

Do not use this lane as the authority for Samsung or Vulkan driver qualification.

### Real Android Devices (ADB + Maestro)

Use physical arm64 devices for GPU conclusions:

1. The selected GGUF is actually compatible with the device runtime.
2. The separate-process GPU probe finishes without killing the app.
3. GPU qualification is correct for that model on that device.
4. A later GPU-on chat send can be validated on the same device.
5. If a smaller model passes but a larger model fails on the same device, the theory is false and qualification must remain model-aware.

## Minimal Execution Order

### Stage 0: Local Safety Checks

Run the unit/runtime checks first:

```bash
./gradlew :apps:mobile-android:testDebugUnitTest \
  --tests com.pocketagent.android.runtime.GpuOffloadQualificationTest \
  --tests com.pocketagent.android.runtime.GatewayAdaptersTest \
  --tests com.pocketagent.android.ui.ModelPolicyTest
```

### Stage 1: Cloud UI/API Gate

Run only the tiny model on Android API 29, 31, and 34:

```bash
bash scripts/dev/maestro-cloud-gpu-model-matrix.sh \
  --async \
  --app-binary-id <app-binary-id> \
  --project-id <project-id> \
  --api-levels 29,31,34 \
  --models tiny
```

Poll status:

```bash
bash scripts/dev/maestro-cloud-upload-status.sh \
  --project-id <project-id> \
  --watch \
  api29:<upload-id> api31:<upload-id> api34:<upload-id>
```

Promote nothing else in cloud until `tiny` finishes cleanly.

### Stage 2: Real-Device Tiny Gate

Run `tiny` on each explicit physical device serial. This script never auto-picks a device.

```bash
bash scripts/dev/maestro-gpu-real-device-matrix.sh \
  --serial <serial-1> \
  --serial <serial-2> \
  --models tiny
```

Recommended device families:

1. Qualcomm/Adreno flagship
2. Qualcomm/Adreno midrange
3. Mali/Exynos class device
4. One device expected to remain ineligible

### Stage 3: Promote On The Same Device Only

If `tiny` qualifies on a device, test `0.8b` on that same device:

```bash
bash scripts/dev/maestro-gpu-real-device-matrix.sh \
  --serial <serial> \
  --models qwen_0_8b
```

If `0.8b` qualifies, test `2b` on that same device:

```bash
bash scripts/dev/maestro-gpu-real-device-matrix.sh \
  --serial <serial> \
  --models qwen_2b
```

### Stage 4: Final Real-Device Send Check

Only after qualification passes for a given model/device pair, run one short GPU-on send flow on that exact pair.

## Decision Rules

1. `tiny` pass and `0.8b` fail on the same device: theory is false.
2. `0.8b` pass and `2b` fail on the same device: theory is false.
3. All models pass on the same device family: evidence supports simplification, but do not generalize beyond tested device families without more data.
4. Cloud pass but real-device fail: trust the real-device result for GPU eligibility.
5. Cloud fail but real-device pass: treat cloud as a UI/API issue, not a GPU verdict.

## Commands Implemented In Repo

1. `bash scripts/dev/maestro-cloud-gpu-model-matrix.sh`
2. `bash scripts/dev/maestro-cloud-upload-status.sh`
3. `bash scripts/dev/maestro-gpu-real-device-matrix.sh`

