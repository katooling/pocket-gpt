# Parallel Work Plan During ENG-13 Native Runtime Implementation

Last updated: 2026-03-04
Owner: Product + Engineering + Marketing
Status: Complete

## Goal

Keep release momentum while `ENG-13` is implemented by running only work that is independent of JNI-native completion.

Outcome: `ENG-13` closure evidence is now complete; this plan is retained as execution history + optimization reference.

## Guardrails

1. Do not publish new performance claims until `NATIVE_JNI` evidence is complete.
2. All copy remains `Validated`-claim only.
3. All Stage-2 closure packets must pass runtime evidence integrity validation.

## Track 1: Engineering (Parallel Support)

### Scope

1. Add closure evidence integrity gate for Stage-2 artifacts.
2. Prevent placeholder/template benchmark packets from being used for closure decisions.
3. Keep quick-test and doc contracts aligned for QA re-run readiness.

### Executed now

1. Added script: `scripts/benchmarks/validate_stage2_runtime_evidence.py`.
2. Added usage docs in:
   - `scripts/dev/README.md`
   - `docs/testing/stage-2-benchmark-runbook.md`
3. Confirmed current blocked run fails integrity gate (missing `model-2b-metrics.csv`).
4. Added model-sweep runtime path to keep model loaded across Scenario A/B per model and reduce repeated native load overhead.
5. Added run-dir hygiene/quality guards in Stage-2 runner (stale artifact cleanup, metric dedupe, scenario-scoped meminfo files).
6. Added warmup skip control (`POCKETGPT_STAGE2_WARMUP_MAX_TOKENS=0`) for faster closure reruns.

### Next handoff (to QA + Runtime Eng)

1. Run validator on each fresh Stage-2 run directory before evidence note publication.
2. Treat any `ADB_FALLBACK`, `not_run`, non-`NATIVE_JNI`, or non-positive runtime metrics as hard fail.

## Track 2: Product (Run In Parallel)

### Scope

1. Execute `PROD-04` monetization hypothesis kickoff and define beta-safe packaging assumptions.
2. Keep pricing work decoupled from final native benchmarks by using scenario bands.

### Executed now

1. Published kickoff doc: `docs/operations/prod-04-monetization-hypothesis-v0.md`.

### Next handoff (Product + Finance + Marketing)

1. Validate willingness-to-pay with beta cohort interviews.
2. Lock post-beta pricing triggers tied to measured quality signals.

## Track 3: Marketing (Run In Parallel)

### Scope

1. Execute `MKT-03` channel experiments using validated claims only.
2. Finish `MKT-04` asset pipeline so publish can happen immediately after ENG-13 closure evidence.

### Executed now

1. Published scorecard template: `docs/operations/mkt-03-7-day-scorecard-template.md`.
2. Kept capture path ready via `scripts/marketing/capture_mobile_demo_assets.sh` and capture runbook.

### Next handoff (Growth + Content)

1. Run first 7-day channel test window and log conversion/activation quality.
2. Capture refreshed screenshots/video when native closure build is available.

## Track 4: Test Coverage Hardening (Run In Parallel)

### Scope

1. Improve unit and instrumentation coverage for newly added UX/runtime-status flows.
2. Update QA release-promotion docs with explicit instrumentation/Maestro pass-id requirements.
3. Operationalize WP-13 usability packet without changing Stage-2 runtime contracts.

### Executed now

1. Added core-flow coverage + ownership matrix in `docs/testing/test-strategy.md`.
2. Added missing unit tests for persistence codec, runtime facade delegation, and runtime benchmark runner.
3. Extended instrumentation smoke for onboarding/runtime-status/privacy and natural-language tool flow.
4. Published usability packet template: `docs/operations/wp-13-usability-gate-packet-template.md`.

### Next handoff (QA + Product + Eng)

1. Run updated weekly QA matrix with WP-13 UX extension rows.
2. Attach latest instrumentation + Maestro pass ids in release-promotion packets.
3. Execute usability packet with first beta cohort before wider promotion.

## Sequencing (While ENG-13 Is Active)

1. Engineering: run Stage-2 integrity validator on every candidate evidence run.
2. Product: complete monetization hypothesis interviews and trigger model.
3. Marketing: execute channel test window and pre-approve claim-safe copy blocks.
4. QA/Product: execute updated test/usability packet cadence in parallel.
5. After ENG-13 closes: refresh assets and publish synchronized launch packet. (Complete)
