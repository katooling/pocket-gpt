# GPU Probe Model Matrix

Date: 2026-03-09
Owner: Codex
Scope: Probe-cache optimization validation plus Maestro Cloud model-equivalence matrix.

## Goal

Check two things with the least duplicate work:

1. The probe-cache changes behave correctly and do not regress local policy behavior.
2. The hypothesis `tiny GGUF pass implies larger model pass on the same device tier` is worth trusting.

## Local Validation

Command:

```bash
./gradlew :apps:mobile-android:testDebugUnitTest \
  --tests com.pocketagent.android.runtime.GpuOffloadQualificationTest \
  --tests com.pocketagent.android.runtime.GatewayAdaptersTest \
  --tests com.pocketagent.android.ui.ChatViewModelTest \
  --tests com.pocketagent.android.ui.ModelPolicyTest \
  --tests com.pocketagent.android.runtime.RemoteLlamaCppRuntimeBridgeTest \
  --tests com.pocketagent.android.runtime.GpuProbeServiceTest \
&& bash -n scripts/dev/maestro-cloud-gpu-model-matrix.sh
```

Result: PASS

Covered areas:
- no-model fast-fail behavior
- same-bits cache reuse behavior
- gateway and policy plumbing
- remote runtime bridge behavior
- Maestro Cloud matrix runner shell syntax

## Cloud Matrix

Project: `proj_01kk5np766e8xtazqxg6p14gye`
App binary id: `400777e95f4ed6ad04bbeea8e887dd9deb5c1d09`
Flow template: `tests/maestro-cloud/scenario-gpu-probe-by-model.template.yaml`
Cloud device family observed: `Pixel 6 x86_64` on API 29/31/34.

### Stage 1: Tiny Model Gate

| Tier | API | Model key | Upload id | Flow run id | Upload status | Flow status | App launched | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Low | 29 | `tiny` v1 | `mupload_01kk9c7rwaetpb5rsaf8cmfasv` | `run_01kk9c7rwvezrtnra172fxqbhe` | `ERROR` | `ERROR` | `true` | Terminal failure. Flow reached device and failed on `Element not found: Start download smollm2-135m-instruct-q4_k_m q4_k_m`. This was a flow issue, not a GPU result. |
| Low | 29 | `tiny` retry v1 | `mupload_01kk9e2j34f84tz5zj9dbfkwky` | `run_01kk9e2j3tf90b8w64fqm8rp79` | `PENDING` | `PENDING` | `false` | Earlier retry before flow correction; left pending and no longer used as the decision run. |
| Low | 29 | `tiny` v2 | `mupload_01kk9ep6kzfpvr28mrry377z0q` | `run_01kk9ep6mkecab8apq4j5vtxrr` | `ERROR` | `ERROR` | `true` | Corrected flow reached device and completed with `Enable GPU acceleration` still absent. |
| Mid | 31 | `tiny` v2 | `mupload_01kk9epcbefzn88xzfgf27ghrc` | `run_01kk9epcc1f7xr3fvknyb6w5ma` | `ERROR` | `ERROR` | `true` | Corrected flow reached device and completed with `Enable GPU acceleration` still absent. |
| High | 34 | `tiny` v2 | `mupload_01kk9epj1qfz0vjpvmjy6ays3n` | `run_01kk9epj2cfvhrws25m9rwz6pe` | `ERROR` | `ERROR` | `true` | Corrected flow reached device and completed with `Enable GPU acceleration` still absent. |
| Mid | 31 | `tiny` v1 | `mupload_01kk9d19p5f5taav435dnyyhj5` | `run_01kk9d19prfqaawa5fe8zh1awq` | `PENDING` | `PENDING` | `false` | Old permissive flow still queued; not used for the qualification decision. |
| High | 34 | `tiny` v1 | `mupload_01kk9d1fzmfpxteq73dx9yagd1` | `run_01kk9d1g08ezqvz276a8qaec6t` | `PENDING` | `PENDING` | `false` | Old permissive flow still queued; not used for the qualification decision. |

### Stage 2: 0.8B Follow-up

Not launched yet.
Rule: run only on tiers where `tiny` reaches a terminal passing result.

### Stage 3: 2B Follow-up

Not launched yet.
Rule: run only on tiers where `0.8b` reaches a terminal passing result.

## Current Read

- Maestro's current docs describe Android cloud configuration in terms of API level selection, with supported Android API levels 29, 30, 31, 33, and 34.
- Maestro's cloud reference also describes the Android emulator specs as x86_64, Google APIs, default API 33, 606x1280 at 244 dpi.
- The same docs explicitly describe iOS model selection, but do not provide an equivalent Android hardware-model matrix.
- That means Maestro Cloud is useful for Android UI/API coverage, but it is not a strong fit for proving Samsung/Adreno Vulkan GPU behavior for this app's arm64-only model artifacts.
- The local code changes are validated.
- The cloud matrix is configured correctly and the uploads are accepted by Maestro Cloud.
- The cloud queue has produced one terminal result so far: the original `api29-tiny` flow failed because the flow targeted a download button that was not reliably visible, so that result was not usable for GPU qualification.
- The corrected qualification flow reached all three cloud API tiers and failed in the same way: `Enable GPU acceleration` never became visible for `tiny`.
- That means the hosted x86_64 Android cloud environment did not qualify GPU even for the smallest GGUF in this app build.
- Because Stage 1 failed on every cloud tier, Stage 2 (`0.8b`) and Stage 3 (`2b`) were correctly not launched in cloud.
- This confirms the split policy: Maestro Cloud can still check provisioning/UI behavior, but real GPU qualification must move to physical arm64 Android devices.

## Poll Command

```bash
source .env
curl -sS -H "Authorization: Bearer $MAESTRO_CLOUD_API_KEY" \
  "https://api.copilot.mobile.dev/v2/project/proj_01kk5np766e8xtazqxg6p14gye/upload/<UPLOAD_ID>" | jq
```

