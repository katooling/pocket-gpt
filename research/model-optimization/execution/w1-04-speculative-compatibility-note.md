# W1-04 Lead B Execution Note: Speculative Draft Compatibility Gate

Date: 2026-03-27  
Owner: Lead B (Runtime speculative policy)

## Implemented

- Added descriptor-driven model-family metadata in `ModelCatalog` and exposed `isSpeculativeDraftCompatible(targetModelId, draftModelId)`.
- Kept speculative defaults unchanged (`smollm3-3b-ud-iq2_xxs` remains the default draft model ID in performance presets).
- Updated `RuntimePlanResolver` speculative gating to require descriptor compatibility in addition to existing RAM and model-path checks.
- Added tests proving:
  - compatible target/draft families remain speculative-enabled;
  - incompatible target/draft families are spec-gated off.

## Residual Gaps

- Compatibility is currently family-scoped at the `modelId` descriptor layer only.
- No quantization/runtime-version compatibility checks are inferred from distribution variants (`q4_0`, `ud_iq2_xxs`) yet.
- Future wave can evolve to explicit pair-level compatibility metadata once runtime/version evidence is available.
