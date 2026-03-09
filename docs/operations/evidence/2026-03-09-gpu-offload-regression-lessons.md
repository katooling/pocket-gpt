# GPU Offload Qualification Notes (2026-03-09)

## Decision Summary
- We replaced static Vulkan feature gating with crash-safe runtime qualification.
- Qualification now runs in a separate app process (`:gpu_probe`) so probe crashes do not crash the UI process.
- GPU toggle authority is now `runtime_supported && probe_status == QUALIFIED`.
- Android Vulkan feature metadata remains logged/advisory for diagnostics.

## Why This Was Needed
- Both target devices can report Vulkan compute/runtime support signals.
- On S22+ (`SM-S906N`), enabling GPU path in-process reproduced native `SIGSEGV` in `nativeLoadModel`.
- Static feature checks were not sufficient to separate "appears supported" from "safe to run."

## Online Research Inputs
1. Android `hasSystemFeature(name, version)` semantics for versioned features:
   - <https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/pm/PackageManager.java>
2. S22+ Vulkan capability sample report (`SM-S906N`, API 1.1):
   - <https://vulkan.gpuinfo.org/displayreport.php?id=15789>
3. A51 Vulkan capability sample report (`SM-A515F`, API 1.1.128):
   - <https://vulkan.gpuinfo.org/displayreport.php?id=24117>
4. Khronos conformance index entry search showing `SM-S906N` as `Vulkan_1_1`:
   - <https://www.khronos.org/conformance/adopters/conformant-products/vulkan>
5. Upstream ggml Vulkan implementation checks (Vulkan 1.2 path + 16-bit feature checks):
   - <https://github.com/ggml-org/llama.cpp/blob/master/ggml/src/ggml-vulkan/ggml-vulkan.cpp>

## Implementation Outcomes
- Added typed policy model:
  - `GpuProbeStatus` (`PENDING`, `QUALIFIED`, `FAILED`)
  - `GpuProbeResult`, `GpuProbeFailureReason`
- Added separate-process probe service:
  - `GpuProbeService` in Android process `:gpu_probe`
  - Layer ladder: `1 -> 2 -> 4 -> 8 -> 16 -> 32`
  - Immediate unload after each trial load
- Added cached qualification keyed by:
  - device fingerprint + native driver diagnostics + app build + model id/version
- Added native Vulkan diagnostics payload export from JNI bridge for deterministic triage.
- Updated runtime/UI policy:
  - `PENDING`: toggle disabled, "Validating GPU support..."
  - `QUALIFIED`: toggle enabled
  - `FAILED`: toggle disabled with reason
- Added runtime demotion path (non-crash load failure -> fail state -> CPU path).
- Kept existing model reload fix so runtime config changes (including GPU settings) actually apply.

## Additional Stability Fix During Validation
- Fixed probe-client unbind behavior on probe-process death.
- Before fix, Android could schedule repeated restarts of crashed probe service connection.
- After fix, probe failure is captured and cached; subsequent app launches use cached `FAILED` state without re-probing for same key.

## Validation Snapshot (Time-Balanced)
- Unit/build:
  - `:apps:mobile-android:testDebugUnitTest` (targeted runtime/probe/send tests) passed.
  - `:packages:native-bridge:test` (bridge + reload behavior) passed.
  - `:apps:mobile-android:assembleDebug` passed.
- On-device short validations:
  - S22+ startup probe:
    - probe process hits `SIGSEGV` in native `nativeLoadModel` at `requested_layers=1`
    - main app process remains alive
    - status persisted as `FAILED` (`PROBE_PROCESS_DIED`)
  - S22+ relaunch:
    - cached `FAILED` reused, no new probe start for same cache key
  - A51 startup:
    - no active probe attempted when model unavailable (`MODEL_UNAVAILABLE`)

## Practical Eligibility Conclusion (Current Build)
- S22+ and A51 are **not qualified** for Vulkan GPU offload in this app build at this time.
- This is based on runtime safety evidence (probe stability), not only static metadata.
- Users remain on CPU path safely; GPU toggle is not exposed as available unless qualification succeeds.
