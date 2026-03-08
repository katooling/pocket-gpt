# inference-adapters

Inference and routing interfaces used by runtime orchestration.

## Scope

- model catalog descriptors
- model artifact lifecycle contracts
- routing policy interfaces
- inference request/response contracts
- device-state inputs for routing decisions

## Current Runtime Path

Primary Android runtime implementation is provided via `packages/native-bridge` (`LlamaCppInferenceModule` + JNI bridge).

## Key Components

- `ModelCatalog`
- `ModelArtifactManager`
- `AdaptiveRoutingPolicy`
- `ImageInputModule` contracts

## Notes

1. This package defines interfaces/contracts; backend-specific bridge behavior lives in `packages/native-bridge`.
2. Routing/model selection behavior is consumed by `packages/app-runtime` orchestration use cases.
