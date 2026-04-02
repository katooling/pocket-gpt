# Engineer Note: Acceptance Gates And Regression Protection

## Topic

How current quality gates interact with optimization work.

## Current State

Pocket GPT has strong gate structure for correctness and lifecycle risk, but benchmark closure is not part of the default promotion path.

Key gate sources:

- `docs/testing/test-strategy.md`
- `docs/testing/runbooks.md`
- `tools/devctl/gates.py`
- `config/devctl/lanes.yaml`
- `scripts/benchmarks/evaluate_thresholds.py`
- `scripts/benchmarks/validate_stage2_runtime_evidence.py`

## Evidence

### Merge-Unblock Gate

`tools/devctl/gates.py` defines merge-unblock as:

- `merge`
- `doctor`
- `android-instrumented`
- risk-triggered lifecycle flow

### Promotion Gate

`tools/devctl/gates.py` and `docs/testing/test-strategy.md` define promotion as:

- `merge`
- `doctor`
- `android-instrumented`
- `maestro`
- strict `journey`
- optional `screenshot-pack`

Notably absent:

- `stage2`
- GPU model matrix
- PocketPal parity comparison

### Stage-2 Threshold Gate

`scripts/benchmarks/evaluate_thresholds.py` currently checks only:

1. Scenario A median first-token <= `2500ms`
2. Scenario A median decode >= `8 tok/s`
3. Scenario B median first-token <= `2500ms`
4. Scenario B median decode >= `4 tok/s`
5. Optional Scenario C decode >= `4 tok/s`

### Stage-2 Runtime Evidence Gate

`scripts/benchmarks/validate_stage2_runtime_evidence.py` verifies:

1. required files exist;
2. CSV headers are present and rows exist;
3. `backend` is `NATIVE_JNI`;
4. required scenario rows exist;
5. `first_token_ms` and `decode_tps` are positive when present;
6. `logcat.txt` contains `NATIVE_JNI` and does not contain `ADB_FALLBACK`.

### Runtime-Tuning Diagnostics Surface

`docs/testing/runtime-tuning-debugging.md` and `RuntimeTuningStore.kt` provide meaningful diagnostics for:

- `RUNTIME_TUNING|...`
- `RUNTIME_TUNING_SAMPLE|...`
- `GPU_OFFLOAD|...`
- `GPU_PROBE|...`
- `MMAP|`
- `FLASH_ATTN|`
- `SPECULATIVE|`
- `PREFIX_CACHE|`

This is good observability, but the default promotion gate does not enforce performance acceptance based on those diagnostics.

## Risk Or Gap

1. A runtime optimization regression can still pass promotion if it preserves correctness and the strict journey lane does not expose the regression as a blocking product signal.
2. Stage-2 closure is treated as a later physical-device signoff lane rather than a routine optimization gate.
3. The threshold validator does not account for:
   - sample count quality
   - min token budget
   - thermal stability
   - realistic decode windows
   - variance across repeated runs
4. The runtime evidence validator checks file integrity more than metric credibility.
5. There is no explicit policy tying certain changed paths or risk labels to mandatory benchmark reruns.

## Recommendation

1. Add a risk-triggered `stage2 quick` requirement for changes under:
   - `packages/native-bridge/**`
   - `apps/mobile-android/src/main/cpp/**`
   - runtime tuning / routing files
2. Keep full `stage2 closure` as release or branch-promotion signoff.
3. Strengthen the threshold gate to reject weak evidence setups:
   - too few runs
   - too few generated tokens
   - unrealistic decode TPS caused by tiny decode windows
4. Extend the runtime evidence validator to assert the presence and freshness of runtime-log-signal artifacts.
5. Require a documented negative-control result for changes that alter GPU qualification or backend selection logic.

## Validation Needed

1. Decide which optimization changes require `stage2 quick` automatically.
2. Decide whether promotion should remain correctness-oriented while stage2 stays separate, or whether stage2 should partially move earlier.
3. Confirm the tightened threshold logic still keeps iteration time acceptable.

## Open Questions

1. Is a path-based stage2 trigger enough, or should we require an explicit `risk:optimization` label?
2. Should strict journey absorb some lightweight performance assertions, or stay purely correctness-oriented?
3. How much benchmark cost is acceptable for day-to-day optimization work?
