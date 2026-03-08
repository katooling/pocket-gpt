# tool-runtime

Local tool execution contracts and deterministic safety behavior.

## Tool Surface

- calculator
- date/time
- notes lookup
- local search
- reminder create

## Safety Contract

1. Allowlist-only tool registry.
2. Schema validation before execution.
3. Deterministic rejection/error mapping for invalid payloads.
4. No arbitrary shell execution path.

## Primary Implementation

- `SafeLocalToolRuntime`
