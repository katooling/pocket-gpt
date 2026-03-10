# Runtime Error Code Registry

Purpose: keep machine-readable error identifiers stable across runtime, provisioning, and UI mapping layers.

## Rules

1. Never reuse an existing code for a different failure mode.
2. Keep codes uppercase snake case.
3. Include user-safe messaging at the mapping boundary (`ViewModel`/UI), not at low-level transport.
4. Add new codes here before shipping.

## Codes

| Code | Owner | Meaning | User-safe default |
| --- | --- | --- | --- |
| `PROVISIONING_IMPORT_SOURCE_UNREADABLE` | Android runtime provisioning | Selected URI/file cannot be opened for import. | "Unable to read the selected model file." |
| `PROVISIONING_IMPORT_PERSIST_FAILED` | Android runtime provisioning | Imported model could not be persisted to managed storage. | "Unable to save the imported model file." |
| `MODEL_MANIFEST_HTTP_ERROR` | Model distribution manifest provider | Remote manifest endpoint returned non-2xx HTTP status. | "Model catalog refresh failed. Falling back to bundled catalog." |
| `UI-SESSION-001` | UI persistence flow | Recoverable session-state corruption detected and reset. | "Saved chat state was corrupted and reset. Refresh runtime checks to continue." |
| `UI-SESSION-002` | UI persistence flow | Fatal session-state load failure. | "Saved chat state could not be loaded. Refresh runtime checks and retry." |

