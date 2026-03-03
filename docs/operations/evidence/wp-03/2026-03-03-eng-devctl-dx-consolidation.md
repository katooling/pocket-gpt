# ENG Devctl DX Consolidation (Maestro + Espresso + Config-Driven Orchestrator)

Date: 2026-03-03  
Owner: Engineering
Scope: CI-safe consolidation of mobile test/dev execution behind a single orchestrator while preserving canonical shell entrypoints.

## Changes Delivered

1. Added config-driven orchestrator:
   - `tools/devctl/main.py`
   - `config/devctl/lanes.yaml`
   - `config/devctl/device.yaml`
   - `config/devctl/stage2.yaml`
2. Preserved canonical command surface with wrapper delegation:
   - `scripts/dev/test.sh`
   - `scripts/dev/device-test.sh`
   - `scripts/dev/bench.sh`
   - `scripts/dev/nightly-hardware-check.sh`
3. Added Android automation lanes:
   - Espresso lane (`devctl lane android-instrumented`)
   - Maestro lane (`devctl lane maestro`)
   - Maestro flow assets under `tests/maestro/`
4. Added deterministic lane error categories and hardening:
   - `CONFIG_ERROR`, `ENVIRONMENT_ERROR`, `DEVICE_ERROR`, `SCHEMA_ERROR`, `THRESHOLD_FAIL`
   - Stage-2 `adb logcat` timeout guard (`adb_logcat_timeout_seconds`)
   - Device preflight required property validation
   - Post-loop crash/OOM summary scan using configured signatures
5. Added regression/unit coverage for orchestrator behavior:
   - `tools/devctl/tests/test_main.py`
6. Updated CI/governance wiring:
   - `.github/workflows/ci.yml`
   - `.github/workflows/nightly-hardware-lane.yml`
   - `.github/pull_request_template.md`
   - `scripts/dev/validate-pr-body.sh`
   - `scripts/dev/evidence-check-changed.sh`
   - `scripts/dev/governance-self-test.sh`
7. Updated canonical docs:
   - `scripts/dev/README.md`
   - `docs/testing/test-strategy.md`
   - `docs/testing/android-dx-and-test-playbook.md`
   - `docs/testing/README.md`
   - `README.md`

## Validation Executed

1. `python3 -m unittest discover -s tools/devctl/tests` -> PASS
2. `bash scripts/dev/docs-drift-check.sh` -> PASS
3. `bash scripts/dev/governance-self-test.sh` -> PASS
4. `bash scripts/dev/test.sh quick` -> PASS
5. `bash scripts/dev/verify.sh` -> PASS
6. `bash scripts/dev/bench.sh stage2 --device SELFTEST --date 2026-03-03` -> Expected `THRESHOLD_FAIL` with placeholder template data; stage-2 error contract verified and no hang on logcat collection

## Notes / Assumptions

1. This work is device-independent and does not claim stage closure by itself.
2. Stage-2 wrapper currently uses template benchmark CSV defaults when scenario CSV inputs are not provided; this intentionally fails threshold checks until real run data is supplied.
3. Device lane framework selection defaults to `both`; explicit `--framework` enforces strict missing-tool failure, while default mode tolerates missing optional framework tooling.
