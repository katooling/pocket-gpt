# UX Error Recovery Guide

Last updated: 2026-03-10  
Owner: Product + Android + QA

## Purpose

Map deterministic UI error codes to user-facing guidance and support actions so recovery is consistent across product, QA, and support.

## Startup Recovery Matrix (`UI-STARTUP-001`)

| Subtype | Trigger Signal | User Guidance | Support/QA Action |
|---|---|---|---|
| Missing model | `Missing runtime model(s)` | Open model setup, import/download required models, then refresh checks | Confirm both required model ids are provisioned and active |
| Checksum mismatch | `CHECKSUM_MISMATCH` | Re-import/re-download model from trusted source | Capture checksum details and source artifact info |
| Provenance mismatch | `PROVENANCE_*_MISMATCH` | Use trusted source and retry provisioning | Capture issuer/signature details and verification logs |
| Runtime incompatible | `RUNTIME_INCOMPATIBLE` | Install compatible model version and refresh checks | Confirm runtime compatibility tag match |
| Backend not native | `Runtime backend is ADB_FALLBACK/UNAVAILABLE` | Validate device/runtime setup, then rerun checks | Capture backend label and startup detail in evidence (`REMOTE_ANDROID_SERVICE` is a valid native backend in remote mode) |
| Template unavailable | `TEMPLATE_UNAVAILABLE` / `model profile missing` | Reinstall/update model package and refresh checks | Confirm required model ids have template profile mappings |

## Other Deterministic Codes

| Code | Trigger | User Guidance | Support/QA Action |
|---|---|---|---|
| `UI-IMG-VAL-001` | invalid/unsupported image input | Re-attach a supported image and retry | Confirm file format/path handling and image validation contract |
| `UI-TOOL-SCHEMA-001` | tool input rejected by schema/safety validation | Rephrase tool request with supported fields only | Verify tool schema contract and safety filters for regression |
| `UI-RUNTIME-001` | runtime/tool/image execution failure not mapped to stricter code | Retry request; if repeated, export diagnostics and check runtime/backend state | Link diagnostics output, backend label, and reproducible input in evidence note |

## Download Failure Guidance (Phase-2)

| Reason | User Copy Intent | Action |
|---|---|---|
| checksum mismatch | artifact integrity failed | re-download from trusted source |
| provenance mismatch | source trust failed | validate source and retry |
| runtime compatibility mismatch | version incompatible | select compatible version |
| insufficient storage | local storage blocked | free storage and retry |
| network unavailable/timeout | connectivity blocked | retry with network |

## Model Not Ready Recovery (User Path)

When runtime state is `Not ready` or `Error`:

1. Open `Advanced` -> `Open model setup`
2. Provision required models (import local files or use the built-in download manager):
   - `Qwen 3.5 0.8B (Q4)`
   - `Qwen 3.5 2B (Q4)`
3. If downloaded version is inactive, activate it
4. Tap `Refresh runtime checks`
5. Confirm runtime status becomes `Ready` and backend is expected

## CTA Hierarchy Contract (NotReady/Error banner)

1. Primary: `Fix model setup`
2. Secondary: `Refresh runtime checks`
3. Tertiary: `Show technical details`

## Evidence Expectations

For each error-related issue filed:

1. Include the error code exactly as shown in UI.
2. Include startup subtype/failure reason (if available).
3. Include runtime status + backend value.
4. Include user action sequence that reproduced the issue.
5. Include diagnostics export when available.
