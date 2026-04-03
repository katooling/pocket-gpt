# Bonsai Support Matrix

Date: 2026-04-02

## Purpose

This document records the current source-backed support position for Prism Bonsai releases in PocketGPT.

It is intentionally stricter than vendor marketing. A release is only treated as supported when PocketGPT has both:

- upstream or vendor documentation that the format/backend path is intended to work, and
- in-repo device evidence that the path actually loads and sends successfully on a representative device class

This matrix also defines the policy vocabulary used by the app:

- `supported`: visible and loadable by default on matching qualified devices
- `experimental`: visible with cautionary copy; not yet treated as broadly supported
- `unsupported`: hidden or blocked because the device/backend path is known not to work or is known to be unusable

## Sources

Official sources reviewed:

- `https://huggingface.co/prism-ml/Bonsai-8B-gguf`
- `https://huggingface.co/prism-ml/Bonsai-4B-gguf`
- `https://huggingface.co/prism-ml/Bonsai-1.7B-gguf`
- `https://www.prismml.com/news/bonsai-8b`

Relevant in-repo evidence reviewed:

- `tmp/bonsai-gated-benchmark-logcat.txt`
- `docs/architecture/llama-vendor-maintenance.md`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/AndroidGpuOffloadSupport.kt`
- `apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime/GpuOffloadQualification.kt`
- `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt`

## What The Sources Actually Support

Stable source facts:

- Prism publishes Bonsai `8B`, `4B`, and `1.7B` GGUF artifacts.
- Public Bonsai releases use `Q1_0_g128`.
- Prism publicly claims Android support.
- Prism publishes one explicit Android datapoint for `Bonsai-8B`: `Samsung S25 Ultra | llama.cpp OpenCL | 19.6 tok/s`.

Important gaps:

- Prism's public quickstarts do not document Android build setup in the same level of detail as CUDA or Metal.
- Public materials do not establish CPU viability for Bonsai on Android.
- Public materials do not establish Hexagon viability for Bonsai on Android.
- Public materials do not establish that all Android GPU families are valid targets.

PocketGPT repo facts:

- PocketGPT has the native vendor seam and packaging required to host specialized formats.
- PocketGPT already contains runtime qualification machinery for Android GPU/OpenCL paths.
- PocketGPT has direct negative evidence for at least one Mali device class:
  - `tmp/bonsai-gated-benchmark-logcat.txt` shows `Unsupported GPU: Mali-G72 r0p1`
  - the same run reports `opencl_device_count=0`
  - the app correctly fast-fails instead of attempting an unusable path

## Support Matrix

Legend:

- `official-claim`: vendor says the path should work, but PocketGPT has not yet qualified it
- `qualified-in-repo`: PocketGPT has direct load/send evidence
- `rejected-in-repo`: PocketGPT has direct failure evidence
- `unknown`: no meaningful proof yet

### Bonsai 8B

| Device class | CPU | OpenCL | Hexagon | Evidence state | PocketGPT policy |
| --- | --- | --- | --- | --- | --- |
| Adreno 7xx+ | unsupported | experimental | unknown | official-claim | experimental until PocketGPT stage2 proof exists |
| Adreno 6xx | unsupported | experimental | unknown | official-claim | experimental until qualified |
| Mali | unsupported | unsupported | n/a | rejected-in-repo | unsupported |
| Unknown / unclassified | unsupported | experimental | unknown | unknown | experimental only if GPU qualification succeeds |

### Bonsai 4B

| Device class | CPU | OpenCL | Hexagon | Evidence state | PocketGPT policy |
| --- | --- | --- | --- | --- | --- |
| Adreno 7xx+ | unsupported | experimental | unknown | official-claim | experimental until PocketGPT proof exists |
| Adreno 6xx | unsupported | experimental | unknown | official-claim | experimental until qualified |
| Mali | unsupported | unsupported | n/a | unknown plus 8B Mali rejection on same format family | unsupported until contrary evidence exists |
| Unknown / unclassified | unsupported | experimental | unknown | unknown | experimental only if GPU qualification succeeds |

### Bonsai 1.7B

| Device class | CPU | OpenCL | Hexagon | Evidence state | PocketGPT policy |
| --- | --- | --- | --- | --- | --- |
| Adreno 7xx+ | unsupported | experimental | unknown | official-claim | experimental until PocketGPT proof exists |
| Adreno 6xx | unsupported | experimental | unknown | official-claim | experimental until qualified |
| Mali | unsupported | unsupported | n/a | unknown plus 8B Mali rejection on same format family | unsupported until contrary evidence exists |
| Unknown / unclassified | unsupported | experimental | unknown | unknown | experimental only if GPU qualification succeeds |

## Policy Decisions Derived From The Matrix

These decisions should drive product behavior until new evidence changes them.

1. `Q1_0_g128` is not a generic "CPU-safe GGUF tier" in PocketGPT.
2. `Q1_0_g128` should be treated as a specialized accelerated format family.
3. Unsupported device classes must not be invited into download-then-fail flows.
4. Unknown device classes should not silently inherit support from a filename or model ID.
5. Qualified GPU evidence is the promotion gate from `experimental` to `supported`.

## Generic Eligibility Model

To avoid Bonsai-specific sprawl, eligibility should be determined by three independent inputs:

1. Release facts
- model family
- model size tier
- quantization / format family
- runtime compatibility tag

2. Device/runtime facts
- ABI
- GPU family
- CPU feature support
- OpenCL automatic eligibility
- measured GPU qualification result

3. Product policy
- whether unsupported releases are hidden or disabled
- whether unknown combinations surface as experimental
- whether download is blocked before load

This separation matters because future PocketGPT model sources may include:

- bundled catalog releases
- remote catalog entries
- direct Hugging Face downloads
- local imports
- remote server-backed models

The selection layer should therefore reason about:

- `what the release needs`
- `what the device has`
- `what PocketGPT has actually proven`

and not about any single model name.

## Immediate Implementation Guidance

The current codebase should treat Bonsai-family releases as follows:

- `supported`
  - only when the device is on a qualified Android GPU/OpenCL path and PocketGPT has matching probe evidence
- `experimental`
  - when the release format is intended for accelerated Android paths, but PocketGPT has only partial or upstream-only evidence
- `unsupported`
  - when the device is CPU-only for that release family
  - when the device class is known rejected, such as the current Mali evidence path
  - when runtime qualification explicitly fails

## Promotion Criteria

A Bonsai tier graduates from `experimental` to `supported` only after all of the following exist for the target device class:

- JNI load proof
- first-send proof
- captured backend identity
- captured GPU qualification result
- explicit unsupported/fallback proof for nearby non-target classes

Until then, PocketGPT should prefer conservative surfacing over optimistic catalog expansion.
