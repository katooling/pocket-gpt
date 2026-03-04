# inference-adapters

Runtime abstraction and adapter contracts.

## Adapters

- baseline adapter: `llama.cpp`
- iOS optimization adapter: Core ML
- Android optimization adapter: LiteRT/NNAPI

## Initial Contracts

- model registry contract
- load/unload lifecycle contract
- token streaming generation contract
- routing policy interface

## Implemented MVP Scaffolding

- `SmokeInferenceModule`: legacy stage-1 streaming smoke adapter retained for local scaffolding only
- `ModelArtifactManager`: stage-2 artifact lifecycle support:
  - multi-version manifest structure (`buildManifest`)
  - deterministic active model/version selection (`setActiveModel`, `setActiveModelVersion`)
  - checksum verification result semantics (`verifyChecksumResult`)
  - CI-safe artifact validation hook (`validateManifest`)
- `AdaptiveRoutingPolicy`: stage-3 battery/thermal/device-aware selection
- `SmokeImageInputModule`: stage-5 image path adapter with deterministic contract behavior:
  - validation error contract (`IMAGE_VALIDATION_ERROR:<code>:<detail>`)
  - normalized success contract (`IMAGE_ANALYSIS(v=1,extension=...,max_tokens=...): ...`)
  - extension allowlist + prompt/path validation guards

Android runtime bridge wiring is implemented in the app layer with:

- `AndroidLlamaCppRuntimeBridge`
- `AndroidLlamaCppInferenceModule`
