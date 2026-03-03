# tool-runtime

Local tool execution contracts and safety model.

## Initial Tool Set

- calculator
- notes lookup
- local search
- reminder scheduling
- date/time

## Rules

- strict schema validation before execution
- no arbitrary shell execution
- policy gate checks for any external access

## Implemented MVP Scaffolding

- `SafeLocalToolRuntime` with allowlisted tools:
  - calculator
  - date_time
  - notes_lookup
  - local_search
  - reminder_create
  - schema-driven JSON argument validation per tool
  - deterministic validation error contract:
    - `TOOL_VALIDATION_ERROR:<ERROR_CODE>:<DETAIL>`
  - strict unknown-field rejection and denylist checks for adversarial fragments
