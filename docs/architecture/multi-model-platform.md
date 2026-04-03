# Multi-Model Platform Migration

## Intent
PocketGPT now has a normalized model-platform slice that separates:
- source normalization,
- artifact bundle modeling,
- sidecar-backed inspection metadata,
- admission policy,
- runtime launch planning.

This keeps built-in models working while making room for remote registries, Hugging Face, and future local imports that are not known at compile time.

## Parameter Inventory
The normalized domain captures the parameter groups that were previously scattered across `ModelCatalog.kt`, manifest JSON, runtime heuristics, and sidecars:
- identity and provenance: source kind, origin ID, publisher, repository, trust policy
- artifact topology: role-tagged artifacts such as `PRIMARY_GGUF`, `MMPROJ`, `DRAFT_MODEL`
- prompt semantics: versioned prompt profile ID, template family, system-role handling, tool and thinking strategy
- capability surface: text, reasoning, image, long-context, structured-output flags
- runtime requirements: bridge support, backend family, runtime tags, RAM/storage floors, preferred context defaults
- inspected parameters: architecture, quantization, quantization version, context length, layers, embeddings, attention heads, vocabulary size

## Current Wiring
- `packages/core-domain/.../NormalizedModelSpec.kt`
  Shared normalized model, variant, artifact, prompt, capability, and runtime requirement types.
- `packages/inference-adapters/.../ModelCatalog.kt`
  Built-in catalog now exports normalized specs instead of only raw descriptors.
- `apps/mobile-android/.../modelspec/NormalizedModelCatalogRegistry.kt`
  Source adapters for built-in, manifest, Hugging Face, and local-import records plus merge behavior.
- `apps/mobile-android/.../StoredModelSidecarMetadata.kt`
  Typed sidecar metadata for artifacts, prompt profile IDs, source kind, and inspected parameters.
- `apps/mobile-android/.../ModelRuntimeLaunchPlanner.kt`
  Bundle-aware launch planning and required-artifact checks.
- `apps/mobile-android/.../ModelAdmissionPolicy.kt`
  Specification-style rules layered on top of the existing eligibility evaluator.
- `apps/mobile-android/.../modelmanager/ModelDownloadManager.kt` and `ModelDownloadExecutor.kt`
  Single user-visible tasks now carry per-artifact bundle state so downloads can stage, verify, resume, and install multi-artifact model versions without changing queue or scheduler semantics.

## Boundary Notes
- The download stack should remain source-agnostic. It consumes distribution versions plus persisted bundle metadata, and should not reach back into `ModelCatalog` to recover prompt or artifact details.
- Manifest and provisioning readers should materialize canonical `artifacts` lists directly instead of relying on late fallback helpers.
- Provisioning/runtime layers may still consult built-in normalized specs for baseline defaults and migration compatibility, but bundle execution should depend on task/spec data that crosses module boundaries explicitly.

## Bundle Download Semantics
- Queueing remains model-version based: one scheduled task represents one version, even if that version resolves to multiple artifacts.
- Each task persists per-artifact download state, resume metadata, verified staging state, and installed paths.
- Downloads are executed sequentially inside a task workspace so aggregate progress can be reported while preserving resume for the current artifact and already-verified earlier artifacts.
- Required artifacts must all verify before install proceeds; side artifacts are installed under deterministic version-scoped filenames and their real paths are written to sidecars.
- Version registration still happens once, after bundle install succeeds, which prevents partially-downloaded bundles from appearing as valid installed models.
- Removal and storage accounting now consider side artifacts, not just the primary GGUF.

## Migration Path
1. Keep current UI and runtime entry points consuming compatibility adapters.
2. Prefer normalized specs in new code instead of reading `ModelCatalog` descriptors directly.
3. Move bundled manifest consumption to bundle-aware artifacts first.
4. Promote dynamic-source fetchers to emit `NormalizedModelSpec` records before touching UI.
5. Expand download/install flows from single-artifact execution to bundle execution.
6. Replace remaining filename heuristics with inspected sidecar metadata wherever practical.

## Governance And Tests
- Adapter contract tests should exist per source adapter.
- Manifest tests should cover bundle artifacts, trust policy, and source kind parsing.
- Sidecar tests should cover both new typed sidecars and legacy GGUF-only sidecars.
- Admission tests should cover backend, runtime-tag, and missing-artifact matrices.
- New built-in model additions should update normalized spec expectations, not only raw descriptor fields.
- Bundle-aware provisioning should keep one task per model-version while persisting per-artifact execution state; device validation should use a real remote-manifest fixture plus auxiliary artifacts, not placeholder files.
