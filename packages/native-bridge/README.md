# native-bridge

Backend bridge layer for local inference runtime execution.

## Responsibilities

- JNI runtime bridge wiring (`NATIVE_JNI` primary path)
- fallback bridge compatibility path (`ADB_FALLBACK`)
- runtime generation config mapping (threads, batching, GPU layers)
- runtime backend/capability reporting

## Key Components

- `NativeJniLlamaCppBridge`
- `AdbDeviceLlamaCppBridge`
- `LlamaCppInferenceModule`
- runtime bridge contracts in `RuntimeBridgeContracts.kt`

## Contract Notes

1. Backend-specific command/process behavior is isolated here.
2. Stream/request semantics are consumed by `packages/app-runtime`.
3. GPU support is capability-reported and consumed by upper layers for UX gating.
