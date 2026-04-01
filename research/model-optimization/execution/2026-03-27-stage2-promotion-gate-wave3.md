# 2026-03-27 Stage2 Promotion Gate Wave 3

## Scope
- `tools/devctl/gates.py`
- `tools/devctl/tests/test_gates.py`

## What changed
- Promotion gate now computes a `stage2 quick` requirement using:
  - optimization-sensitive changed-file patterns
  - explicit `--risk-label` values already used in gate policy
- When required, promotion runs:
  - `python3 tools/devctl/main.py lane stage2 --profile quick`
- When not required, promotion records a non-blocking skipped `stage2-quick` step with an explicit reason.
- Promotion report metadata now includes:
  - `risk_labels`
  - `stage2_quick_required`
  - `stage2_quick_reason`
  - `changed_files_count`

## Definition of done evidence
- Positive path coverage:
  - optimization-sensitive change triggers `stage2-quick` execution in promotion flow.
- Negative path coverage:
  - low-risk change records skipped non-blocking `stage2-quick` step with `low-risk-change`.
- Trigger function coverage:
  - direct helper tests for trigger and skip decisions.

## Residual gap
- Triggering is path/label based and intentionally conservative; it does not yet classify semantic risk from file diffs.
