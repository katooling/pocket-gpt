# QA-05 Scenario C + Memory Notes

## Scenario Summary
- A: first_token_ms=65, decode_tps=62.500000, pass=true
- B: first_token_ms=63, decode_tps=65.573770, pass=true
- C: first_token_ms=0, decode_tps=4000.000000, pass=true

## Qualitative Observation
- Scenario C: StageBenchmarkResult(scenario=C, p50FirstTokenMs=0, p50DecodeTokensPerSecond=4000.0, pass=true)
- Scenario C output shape is deterministic and normalized (`IMAGE_ANALYSIS(v=1,...)`).
- Memory persistence is validated via SQLite module tests (see c5-memory-tests.log).
