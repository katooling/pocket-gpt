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

- `SmokeInferenceModule`: stage-1 streaming smoke model adapter
- `ModelArtifactManager`: stage-2 model manifest and checksum helpers
- `AdaptiveRoutingPolicy`: stage-3 battery/thermal/device-aware selection
- `SmokeImageInputModule`: stage-5 image path placeholder for local integration
