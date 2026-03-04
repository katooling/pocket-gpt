# Privacy and Security Model

## Privacy Promise

PocketAgent is local-first by default:

1. Inference runs on-device.
2. Conversation and memory storage remain on-device.
3. No background upload of user prompts/responses.

## Data Classification

1. Sensitive user content: prompts, responses, notes, image text
2. Operational metadata: latency, memory, thermal snapshots
3. Policy state: permissions, retention choices, opt-in flags

## Data Flow Rules

1. Sensitive user content stays local unless user explicitly opts into an external action.
2. Tool calls execute through a local sandbox with schema validation.
3. Any network-capable action must pass `PolicyModule` checks.

## Threat Model

### Threats

1. Prompt/data exfiltration through hidden network calls
2. Arbitrary execution via malformed model tool output
3. Retention drift (data stored longer than stated policy)
4. Debug log leakage of sensitive content

### Controls

1. Default deny network policy for assistant actions
2. Strict tool schema validation and allowlisted tool set
3. Retention policy enforcement jobs with auditable settings
4. Privacy-safe logging (no raw prompt/response content in diagnostics by default)

## Security Controls

1. Model artifact integrity checks (hash verification)
2. Local encrypted storage where OS support is available
3. Least-privilege permissions for file/media access
4. Secure update channel for model/tool manifests

## User Controls

1. Offline mode toggle
2. Per-tool enable/disable settings
3. Data retention window selector
4. Local data delete/reset action

## Compliance Posture (Foundational)

1. Privacy claims map directly to implemented controls
2. User consent required for any optional cloud path
3. Documented data inventory and retention behavior

## Implementation Coverage (As Of 2026-03-04)

| Control Area | Planned Guarantee | Current Coverage |
|---|---|---|
| Local inference default | No cloud-required inference path | Partial (closure-path startup checks now block `ADB_FALLBACK`; native JNI proof and full production path still in progress) |
| Local data retention policy | explicit retention window + pruning | Partial (policy interface present; in-memory memory backend) |
| Tool safety | strict schema validation + allowlist | Partial (allowlist + payload fragment blocking) |
| Diagnostics privacy | no raw prompt/response by default | Partial (diagnostics are metric-only strings; needs explicit redaction tests) |
| Network gating | explicit policy checks per action | Partial (policy module exists; enforcement not integrated with platform network stack yet) |
| Voice privacy (future STT/TTS) | equivalent controls to text/image paths | Planned (post-MVP) |

Use `docs/roadmap/next-steps-execution-plan.md` as the source of truth for closure of these gaps.
