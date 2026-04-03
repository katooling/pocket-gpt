# Model Selection And Qualification Refactor Plan

Date: 2026-04-02

## Why This Exists

The Bonsai support-expansion work added a first generic eligibility layer, but today's real-device run shows the app still needs a larger refactor before model download and load decisions can be treated as reliable, scalable, and backend-aware.

This document turns that result into a concrete follow-up plan.

## What Today's Device Evidence Proved

Target device:

- `SM-S906N`
- `ro.board.platform=taro`
- `ro.hardware=qcom`

Real-device commands run:

1. Install current app and test APKs on the selected Snapdragon device.
2. Push `Bonsai-1.7B.gguf` and `Bonsai-4B.gguf` to `/sdcard/Download/com.pocketagent.android/models/`.
3. Run `SpecializedFormatQualificationInstrumentationTest#q1TierLoadQualificationMatchesExpectation` for:
   - `bonsai-1.7b-q1_0_g128`
   - `bonsai-4b-q1_0_g128`

Observed result:

- The device passes static advisory checks:
  - Adreno family
  - arm64
  - dotprod
  - i8mm
  - Adreno generation 7
- The runtime still fails actual accelerator discovery:
  - `ggml_opencl: platform IDs not available`
  - `registered backend OpenCL (0 devices)`
  - `opencl_device_count=0`
  - `gpu_runtime_supported=false`
- The specialized-format load is correctly rejected for both smaller Bonsai tiers before a bogus CPU fallback path is attempted.

Practical conclusion:

- `bonsai-1.7b-q1_0_g128` and `bonsai-4b-q1_0_g128` should remain out of the shipped manifest for now.
- The current blocker is not model size-tier qualification. The blocker is that the runtime/backend qualification stack still treats this phone as statically eligible but runtime-ineligible.

## Current Architecture Snapshot

PocketGPT already has useful building blocks:

1. Static model facts
- `ModelCatalog.kt`
- manifest entries in `model-distribution-catalog.json`

2. Device and runtime signals
- `AndroidGpuOffloadSupport.kt`
- `GpuOffloadQualification.kt`
- `RuntimeGateway.kt`

3. Eligibility evaluation
- `ModelCatalogEligibility.kt`

4. Native format and backend enforcement
- `OpenClRuntimePolicy.kt`
- `NativeJniLlamaCppBridge.kt`

5. UI surfacing
- `ModelProvisioningViewModel.kt`
- `ModelSheet.kt`

That is directionally right, but it still has important structural gaps.

## Gaps Exposed By Today's Run

### 1. Static eligibility and runtime capability are still too loosely coupled

The current eligibility layer consumes runtime support as a boolean, but the real failure mode is backend-specific:

- static device advisory says "Adreno/OpenCL-eligible"
- runtime says "no actual OpenCL platform/devices"

The system needs to reason about:

- requested format family
- required backend family
- discovered backend families
- qualified backend families

not just `supportsGpuOffload=true|false`.

### 2. Probe policy is backend-hardcoded

`GpuOffloadQualification.kt` currently hardcodes probe requests to `backendProfile = "opencl"`.

That means the qualification system cannot naturally evolve toward:

- OpenCL-only formats
- Vulkan-safe standard formats
- Hexagon-specific tiers
- future backend mixes per model family

### 3. Native build configuration currently narrows the backend surface

`CMakeLists.txt` explicitly sets:

- `GGML_VULKAN OFF`
- `GGML_OPENCL ON`

This may be intentional for Bonsai enablement, but architecturally it means the product is now making release decisions from a single backend assumption. Historical repo evidence shows nearby Snapdragon runs previously reported `compiled_backend=vulkan|supported=true`, while today's build reports `compiled_backend=opencl|supported=false`.

Even if Bonsai itself ultimately remains OpenCL-only, the app architecture should not collapse all GPU reasoning into OpenCL reasoning.

### 4. Eligibility is enforced mostly at the UI layer

`ModelSheet.kt` disables download/load buttons based on eligibility, but `ModelProvisioningViewModel.kt` and `ProvisioningGateway.kt` currently pass `enqueueDownload()`, `importModelFromUri()`, and `loadInstalledModel()` straight through without a shared policy guard.

That creates drift risk for:

- non-UI entry points
- deep links
- automation
- future background download/import flows
- future Hugging Face or local-import flows

### 5. The source model is still catalog-first

PocketGPT's current design is strongest for shipped manifest entries plus local versions tied to known model IDs. It is not yet shaped around a unified source model for:

- bundled catalog releases
- user-imported files
- future Hugging Face direct downloads
- future remote/runtime-backed models

### 6. Qualification evidence is not yet a first-class product input

We now have:

- static advisory facts
- runtime backend diagnostics
- probe results
- instrumentation evidence

but those are not yet normalized into one durable capability/evidence record that the rest of the app can consume.

## Comparator Read: What To Borrow From PocketPal

PocketPal is useful as a systems comparator, not as a template.

Patterns worth borrowing:

1. Treat model source as first-class
- preset
- local
- Hugging Face
- remote

2. Keep lightweight import paths
- local files enter the system first
- metadata enrichment happens after admission

3. Store and reuse model/runtime metadata
- GGUF metadata
- memory estimates
- benchmark/runtime config evidence

4. Duplicate enforcement where it matters
- UI warnings are not the only gate
- deeper download/runtime services still enforce safety checks

Patterns to avoid copying directly:

1. Too much heuristic policy in the app/store layer
2. Too many low-level runtime knobs leaking directly into product logic
3. Source handling without a stronger backend qualification model

## Target Architecture

The follow-up refactor should split responsibilities into seven explicit layers.

### Layer 1: Model Source Registry

Owns where a model came from:

- bundled catalog
- local import
- Hugging Face
- remote

Core outputs:

- stable source id
- source type
- user-facing provenance
- artifact locator
- raw model metadata if known

### Layer 2: Release Requirements

Pure facts about what an artifact needs:

- format family
- quantization
- minimum RAM/storage guidance
- multimodal/projector requirements
- supported backend families
- whether accelerated backend is optional or mandatory

Important: this layer should not know about specific devices.

### Layer 3: Device Capability Snapshot

Pure facts about the current device:

- ABI
- CPU features
- GPU family and generation
- storage headroom
- memory class and available headroom
- OS/runtime environment

Important: this is advisory, not proof.

### Layer 4: Runtime Backend Capability Snapshot

Observed backend truth from the current build/runtime:

- compiled backends
- discovered backends
- active backend
- runtime-supported backends
- backend-specific feature flags
- per-backend qualification result
- backend failure reasons

This layer is the missing bridge between device heuristics and product decisions.

### Layer 5: Eligibility Policy Engine

Consumes:

- source registry entry
- release requirements
- device capability snapshot
- runtime backend capability snapshot
- product policy

Produces separate decisions for:

- catalog visibility
- search visibility
- download allowed
- import allowed
- activation allowed
- load allowed
- route allowed

The output should be backend-aware and reason-coded.

### Layer 6: Enforcement Adapters

The same policy result must be enforced consistently in:

- catalog UI
- local import UI
- download scheduling
- activation/default-version mutation
- explicit load requests
- routing/autoload paths

The UI should explain the decision, but should not be the only gate.

### Layer 7: Evidence And Diagnostics

Persist normalized evidence for support and regressions:

- benchmark runs
- probe outcomes
- backend diagnostics snapshots
- model-load outcomes
- first-send outcomes

This becomes the promotion input for future manifest additions and backend rollouts.

## Phase Plan

### Phase 0: Close The Snapdragon Runtime Gap

Goal: determine whether the current blocker is:

- OpenCL runtime packaging
- backend discovery regression
- Qualcomm vendor/Android 16 behavior
- an intentional Vulkan-to-OpenCL tradeoff

Work:

1. Run a minimal backend matrix on the same phone for a standard model and a Bonsai model.
2. Capture `backendDiagnosticsJson()` before and after app startup and after explicit load.
3. Compare current OpenCL-only build behavior against a branch/config that restores Vulkan for standard formats.
4. Decide whether the runtime should support:
   - OpenCL only
   - Vulkan + OpenCL
   - format-specific backend families

Deliverable:

- a short backend decision note with reproducible logs

### Phase 1: Introduce Backend-Family-Aware Capability Modeling

Goal: stop modeling GPU support as one boolean.

Work:

1. Add a normalized backend capability model:
   - `OPENCL`
   - `VULKAN`
   - `HEXAGON`
   - `CPU`
2. Record per-backend:
   - compiled
   - discovered
   - runtime-supported
   - qualified
   - feature-qualified
3. Make `ModelRuntimeFormats` map to required backend families instead of only "requires qualified GPU".

Deliverable:

- one capability snapshot that eligibility, UI, and native enforcement all consume

### Phase 2: Move Eligibility From UI Advice To Shared Policy

Goal: make the same decision apply everywhere.

Work:

1. Keep `ModelCatalogEligibilityEvaluator`, but expand it into a more general model admission policy engine.
2. Enforce policy in:
   - `ProvisioningGateway`
   - download scheduler entry points
   - import entry points
   - explicit load entry points
3. Leave UI components as presenters of those decisions, not owners of them.

Deliverable:

- impossible to reach a blocked download/load path through a non-UI code path

### Phase 3: Unify Model Sources

Goal: prepare for future Hugging Face direct downloads and generic imports.

Work:

1. Introduce a source registry abstraction that separates:
   - source identity
   - artifact identity
   - runtime identity
2. Support user-imported models without requiring a pre-baked shipped model ID.
3. Allow imported or remote-discovered artifacts to be evaluated through the same release-requirements and eligibility engine.

Deliverable:

- shipped catalog entries and user-sourced entries share one qualification path

### Phase 4: Tie Qualification Evidence To Promotion Rules

Goal: make model expansion and backend rollout evidence-driven.

Work:

1. Store benchmark/probe/load/send evidence in a structured form.
2. Define promotion rules for new format families and new model tiers.
3. Require manifest additions to cite:
   - device class
   - backend family
   - load proof
   - first-send proof
   - failure/fallback proof

Deliverable:

- support expansion becomes a policy operation, not a one-off code tweak

## Immediate Priority Order

1. Diagnose the Snapdragon OpenCL runtime gap before adding any more Bonsai SKUs.
2. Refactor capability modeling so backend family is explicit.
3. Move download/load/import gating below the UI boundary.
4. Only then add Hugging Face direct-download support or broaden model-source admission.

## Success Criteria

This refactor is done when all are true:

1. A model artifact can be evaluated without assuming it came from the shipped manifest.
2. Backend support is represented per backend family, not as one generic GPU boolean.
3. Download, import, activation, load, and route decisions all come from the same policy engine.
4. Native bridge validation and app-layer eligibility decisions produce the same answer for the same artifact/device/backend tuple.
5. Promotion of new model families or quantizations requires structured evidence, not ad hoc confidence.
