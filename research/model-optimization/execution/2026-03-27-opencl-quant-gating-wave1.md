# 2026-03-27 OpenCL Quant Gating Wave 1

## Change
- `NativeJniLlamaCppBridge` now evaluates `ModelLoadOptions.modelVersion` first for OpenCL quant compatibility.
- Explicit version tags (for example `q4_0`, `q8_0`, `q6_k`, `f16`, `f32`) keep GPU layers enabled when OpenCL is active.
- Explicit unsupported tags (for example `ud_iq2_xxs` / IQ and other recognized non-safe quants) demote GPU layers to CPU.
- Existing filename/model-id heuristics remain as fallback when `modelVersion` is absent or does not include a recognizable quant tag.

## Residual Gaps
- Compatibility is still regex-based and not GGUF-metadata-based, so malformed/non-standard version strings can miss classification and fall back to heuristics.
- Safe quant allowlist is intentionally narrow and should be revisited if native OpenCL backend support expands.
