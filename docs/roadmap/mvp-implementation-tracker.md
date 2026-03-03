# MVP Implementation Tracker (Android-First)

Use this tracker to execute the six MVP stages against explicit entry/exit criteria.

## Stage Status

| Stage | Description | Status | Required Evidence |
|---|---|---|---|
| 1 | Android text-only runtime slice with smoke model | Implemented scaffolding | startup check output, 10-run short chat logs |
| 2 | Qwen 0.8B swap + scenario A/B thresholds | Ready to execute on device | benchmark CSV + threshold report |
| 3 | Routing/policy/observability integration | Implemented scaffolding | downgrade test logs, diagnostics export |
| 4 | Schema-safe tool runtime v1 | Implemented scaffolding | malformed call rejection tests |
| 5 | Memory v1 + image input v1 | Implemented scaffolding | scenario C benchmark + quality rubric notes |
| 6 | Hardening + privacy + beta packet | Implemented docs + guard scaffolding | soak test logs, go/no-go packet |

## Stage 1 Checklist

- [ ] Build and run Stage runner
- [ ] Capture first-token and total latency
- [ ] Confirm no crashes/OOM in 10 short runs

## Stage 2 Checklist

- [ ] Replace smoke model artifact with real Qwen `0.8B Q4`
- [ ] Run Scenario A/B from benchmark protocol
- [ ] Evaluate thresholds and record pass/fail

## Stage 3 Checklist

- [ ] Validate low-battery downgrade behavior
- [ ] Validate high-thermal downgrade behavior
- [ ] Export diagnostics report

## Stage 4 Checklist

- [ ] Run positive tool tests (calculator/date_time)
- [ ] Run malformed JSON and blocked payload tests
- [ ] Verify allowlist enforcement

## Stage 5 Checklist

- [ ] Validate memory retrieval relevance on follow-up prompts
- [ ] Run image path Scenario C benchmarks
- [ ] Record latency and output quality notes

## Stage 6 Checklist

- [ ] Run 30-minute soak test
- [ ] Verify resilience guards behavior
- [ ] Finalize beta go/no-go packet
