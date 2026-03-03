# MVP Beta Go/No-Go Packet

## Decision Date

TBD

## Scope

Android-first MVP with:

1. offline text chat
2. local tools
3. memory v1
4. single-image input

## Go Criteria

1. Scenario A/B/C thresholds pass on target Android mid-tier devices.
2. No blocker privacy/security findings.
3. No repeatable OOM/ANR in soak tests.
4. Routing downgrade policy behaves correctly under stress.

## No-Go Triggers

1. Sustained thermal throttling within first 5 minutes on target devices.
2. Frequent OOM or startup instability.
3. Tool validation bypass or unsafe execution path.
4. Offline-only policy fails to prevent network usage.

## Evidence Checklist

- [ ] benchmark CSV for scenarios A/B/C
- [ ] threshold evaluation report
- [ ] diagnostics export samples
- [ ] soak test report
- [ ] risk register review update

## Open Risks and Owners

| Risk | Owner | Status | Mitigation |
|---|---|---|---|
| Qwen 0.8B model packaging and checksum flow | Runtime | Open | finalize artifact manifest and SHA handling |
| Android thermal variability by OEM | Android | Open | profile on at least 2 device classes |
| Tool safety edge cases | Platform | Open | add strict schema validation tests |
| Memory relevance quality | Core/AI | Open | improve retrieval scoring and summary generation |

## Recommendation

Current recommendation: **Conditional Go** pending physical device benchmark and soak test evidence.
