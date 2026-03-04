# WP-09 QA Incident Triage Template

Date: 2026-03-04  
Owner: QA Lead  
Usage: Beta-ops incident intake and triage

## Intake Fields (Required)

1. Incident ID:
2. Reported at (UTC):
3. Reporter:
4. App build/version:
5. Device model:
6. Android version:
7. Workflow area (`chat`/`session`/`image`/`tool`/`routing`/`other`):
8. Repro steps:
9. Expected behavior:
10. Actual behavior:
11. Raw evidence links:
   - `scripts/benchmarks/runs/2026-03-04/RR8NB087YTF/qa-06-soak-20260304-095133/`
   - logcat file path
   - screenshot/video path (if available)

## Severity Model

1. `S0` - launch blocker, data/safety/privacy critical, no workaround.
2. `S1` - severe user-facing failure, limited workaround.
3. `S2` - functional defect with acceptable workaround.
4. `S3` - minor issue/cosmetic/non-critical.

## Routing and SLA

1. `S0`: page Engineering + Product immediately; first response <= 30 minutes.
2. `S1`: assign Engineering owner same day; first response <= 4 hours.
3. `S2`: queue next sprint or hotfix candidate; first response <= 1 business day.
4. `S3`: backlog with triage note; first response <= 2 business days.

## Triage Decision Record

1. Severity assigned:
2. Owner assigned:
3. Priority (`P0`/`P1`/`P2`/`P3`):
4. Repro status (`confirmed`/`not reproduced`/`needs-info`):
5. Next action:
6. ETA target:
7. Status (`open`/`mitigated`/`fixed`/`closed`):

## Exit Criteria

1. Fix validated with reproduction path.
2. Evidence updated with post-fix verification.
3. Incident marked closed with owner/date.
