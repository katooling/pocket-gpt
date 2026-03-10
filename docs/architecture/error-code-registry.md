# Runtime Error Code Registry

Last updated: 2026-03-10

Purpose: keep machine-readable error identifiers stable across runtime, provisioning, and UI mapping layers.

## Rules

1. Never reuse an existing code for a different failure mode.
2. Keep codes uppercase snake case, except UI-facing numeric contract codes (`UI-...`).
3. Include user-safe messaging at the mapping boundary (`ViewModel`/UI), not at low-level transport.
4. Add new codes here before shipping.

## UI Contract Codes

| Code | Owner | Meaning | User-safe default |
| --- | --- | --- | --- |
| `UI-STARTUP-001` | UI error mapper | Startup/readiness checks failed. | "Runtime setup is incomplete. Open model setup, refresh checks, and retry." |
| `UI-IMG-VAL-001` | UI error mapper | Image validation rejected input. | "That image could not be processed. Use a supported file and try again." |
| `UI-TOOL-SCHEMA-001` | UI error mapper | Tool input failed schema/safety validation. | "That tool request was rejected for safety. Update input and retry." |
| `UI-RUNTIME-001` | UI error mapper | Runtime/tool/image execution failure not mapped to stricter code. | "Request failed. Please try again." |
| `UI-SESSION-001` | UI persistence flow | Recoverable session-state corruption detected and reset. | "Saved chat state was corrupted and reset. Refresh runtime checks to continue." |
| `UI-SESSION-002` | UI persistence flow | Fatal session-state load failure. | "Saved chat state could not be loaded. Refresh runtime checks and retry." |

## Runtime Transport Codes

| Code | Owner | Meaning | User-safe default |
| --- | --- | --- | --- |
| `REMOTE_PROCESS_DIED` | Remote runtime transport | Runtime service process disconnected/crashed during request. | "Runtime service disconnected. Retry request." |
| `REMOTE_BIND_FAILED` | Remote runtime transport | App failed to bind to runtime service. | "Runtime service unavailable. Refresh runtime checks and retry." |
| `REMOTE_TIMEOUT` | Remote runtime transport | Runtime service command or generation timed out. | "Request timed out. Confirm runtime readiness and retry." |
| `REMOTE_BUSY` | Runtime service | Runtime service is busy with another operation. | "Runtime is busy. Retry in a moment." |
| `REMOTE_RUNTIME_ERROR` | Remote runtime bridge/service | Runtime service returned a generic execution failure. | "Runtime request failed. Retry." |
| `REMOTE_MODEL_NOT_LOADED` | Remote runtime bridge | Generate was requested before model load/activation completed. | "Model is not loaded. Fix model setup and retry." |

## Provisioning and Manifest Domain Codes

| Code | Owner | Meaning | User-safe default |
| --- | --- | --- | --- |
| `PROVISIONING_IMPORT_SOURCE_UNREADABLE` | Android runtime provisioning | Selected URI/file cannot be opened for import. | "Unable to read the selected model file." |
| `PROVISIONING_IMPORT_PERSIST_FAILED` | Android runtime provisioning | Imported model could not be persisted to managed storage. | "Unable to save the imported model file." |
| `MODEL_MANIFEST_HTTP_ERROR` | Model distribution manifest provider | Remote manifest endpoint returned non-2xx HTTP status. | "Model catalog refresh failed. Falling back to bundled catalog." |
| `MODEL_MANIFEST_REMOTE_FETCH_FAILED` | Model distribution manifest provider | Remote manifest fetch failed; bundled fallback path used. | "Model catalog refresh failed. Falling back to bundled catalog." |
| `MODEL_MANIFEST_BUNDLED_UNAVAILABLE` | Model distribution manifest provider | Bundled manifest asset/context unavailable. | "Bundled model catalog is unavailable in this build/context." |
| `MODEL_MANIFEST_BUNDLED_INVALID` | Model distribution manifest provider | Bundled manifest content invalid/empty/parse failure. | "Bundled model catalog is invalid. Use a valid build or import path." |

## Provisioning Recovery Signal Codes

These are emitted in `RuntimeProvisioningSnapshot.recoverableCorruptions` and shown in model setup diagnostics.

| Code | Owner | Meaning |
| --- | --- | --- |
| `MODEL_LOCAL_FILE_MISSING` | Runtime provisioning snapshot | Active model metadata exists but file path no longer exists. |
| `PROVISIONING_VERSIONS_ROW_CORRUPT` | Provisioning store codec | One or more stored version rows were invalid and dropped. |
| `PROVISIONING_ACTIVE_VERSION_ORPHANED` | Provisioning store reconciler | Active version pointer no longer maps to an installed version. |
| `PROVISIONING_VERSIONS_JSON_CORRUPT` | Provisioning store codec | Stored versions JSON payload was corrupted and reset/recovered. |
| `PROVISIONING_VERSIONS_RECOVERED_FROM_DISCOVERY` | Provisioning store discovery | Versions metadata rebuilt from discovered local model files. |
| `PROVISIONING_DYNAMIC_MODEL_IDS_CORRUPT` | Provisioning dynamic model registry | Dynamic model id registry payload was corrupted and reset. |
| `PROVISIONING_DISCOVERY_FILES_SKIPPED` | Provisioning migration discovery | Some discovered model files were skipped during migration. |
| `PROVISIONING_METADATA_JSON_CORRUPT` | Provisioning migration discovery | Metadata file JSON parse failure was detected and backed up. |
| `PROVISIONING_DISCOVERY_METADATA_SKIPPED` | Provisioning migration discovery | Some metadata entries were skipped during discovery migration. |
