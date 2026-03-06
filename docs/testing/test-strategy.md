# Test Strategy

Last updated: 2026-03-06

## Excellence Policy

Engineering and quality excellence are always required.

1. Fast feedback is required, but never at the cost of correctness.
2. Release confidence requires deterministic tests plus reproducible device evidence.
3. Known quality debt must be tracked explicitly with owner and closure date.
4. "Works on my device" is not acceptance.

## Source of truth

- Command contract: `scripts/dev/README.md`
- Android device execution details: `docs/testing/android-dx-and-test-playbook.md`

## Goals

1. Catch regressions early with fast local feedback.
2. Prove reliability and safety on real Android hardware.
3. Prove user-facing MVP usability through deterministic UI acceptance flows.
4. Produce reproducible evidence for go/no-go decisions.

## Test Pyramid

1. Unit tests (required on every change)
2. Module integration tests
3. UI state/wiring tests (Compose + ViewModel + facade)
4. Device validation tests (Scenario A/B/C + UI acceptance)
5. Soak/reliability tests

## Lane Architecture and Ownership Matrix

| Lane | Owner | Goal | Cadence | Main Commands | Primary Artifacts |
|---|---|---|---|---|---|
| L0 Dev fast lane | Engineering | fast local confidence with changed-file selection | every change | `bash scripts/dev/test.sh fast` | `build/devctl/recommended-lanes.txt`, local test output |
| L1 Core contracts lane | Engineering | run full module + Android unit safety net | before PR | `bash scripts/dev/test.sh core` | unit test reports |
| L2 Merge gate lane | Engineering + CI | clean merge gate and governance checks | every PR | `bash scripts/dev/test.sh merge` + governance checks | CI reports and artifacts |
| L3 Android smoke lane | QA + Engineering | device/emulator UI wiring confidence | weekly + pre-promotion | `python3 tools/devctl/main.py lane android-instrumented`, `python3 tools/devctl/main.py lane maestro` | weekly QA packets under `docs/operations/evidence/wp-09/` |
| L4 Stage-2 quick lane | Engineering | fast native runtime sanity/perf loop | runtime/native edits | `bash scripts/dev/bench.sh stage2 --profile quick --device <id>` | `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...` |
| L5 Stage-2 closure lane | Engineering + QA | strict closure evidence (native JNI + thresholds + validator) | closure/signoff windows | `bash scripts/dev/bench.sh stage2 --profile closure --device <id>` | `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...` + evidence note |
| L6 Real-runtime app-path lane | Engineering + QA | prove user-message app path on native runtime in RC builds | release-candidate windows | `adb shell am instrument ... -e stage2_enable_app_path_test true -e stage2_model_0_8b_path ... -e stage2_model_2b_path ... com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner` | instrumentation output + linked evidence note |
| Product usability lane | Product + QA + Design | release decision from usability signals | beta cohort windows | WP-13 usability packet workflow | `docs/operations/evidence/wp-13/...` |

## Core Flow Coverage Matrix (Mandatory)

| Core Flow | Unit/Module Coverage | Instrumented Coverage | Maestro Coverage | Device/Operational Coverage | Owner |
|---|---|---|---|---|---|
| Startup and launch status | `ChatViewModelTest`, startup-check mapping tests | launch smoke with runtime-status assertions | scenario launch step | stage checks baseline signal | Eng + QA |
| Send and streaming response | ViewModel stream tests | send-message UI smoke | `tests/maestro/scenario-a.yaml` | weekly matrix run | Eng + QA |
| Session create/switch/restore | ViewModel + persistence codec tests | drawer/session sanity checks | scenario continuity check (weekly extension) | weekly matrix run | Eng + QA |
| Image attach success/failure | ViewModel/image module tests | image attach instrumentation checks | optional workflow in QA runs | required-tier weekly checks | Eng + QA |
| Tool invocation + safety rejection | tool runtime/schema tests + ViewModel tool mapping | natural-language tool UI assertions | tool flow in `tests/maestro/scenario-b.yaml` | weekly matrix + security review | Eng + QA + Security |
| Diagnostics export + redaction | diagnostics/redaction tests | diagnostics UI assertion | scenario-b diagnostics step | QA release promotion checklist | Eng + QA + Security |
| Offline/network policy enforcement | policy/network tests | launch sanity in offline mode | n/a | WP-12/weekly policy references | Eng + QA + Security |
| Runtime/model readiness transitions | ViewModel runtime state tests | status-chip and advanced-sheet checks | n/a | stage-2 and startup checks | Eng + QA |
| Model manager phase-2 (download/version/storage) | `UiErrorMapperTest`, `ChatViewModelTest`, `NetworkPolicyManifestConfigTest` | model sheet journey instrumentation | Maestro NotReady recovery extension | RC journey evidence + WP-13 packet linkage | Eng + QA + Product |
| Scenario C continuity/user journey | ViewModel session + image tests | smoke regression assertions | `tests/maestro/scenario-c.yaml` | weekly matrix + usability packet | QA + Product + Eng |

Coverage rule:

1. Every core flow must have at least one deterministic unit/module assertion.
2. User-visible critical flows must also have instrumentation or Maestro assertion.
3. Runtime/performance assertions remain in device lanes only.

## Release Gates

| Gate | Required For |
|---|---|
| Unit tests green on touched modules | every PR |
| No new high-severity lint/test failures | every PR |
| Scenario A/B thresholds pass on target device | Stage 2+ |
| Policy/tool safety regressions blocked | Stage 3+ |
| Scenario C + memory quality checks pass | Stage 5+ |
| Soak test + go/no-go packet complete | Stage 6 beta |
| Closure-path startup checks fail on `ADB_FALLBACK` backend unless explicitly override-enabled for local scaffolding | Stage 6.5+ |
| UI-01..UI-10 acceptance suite pass | WP-11 close + external beta signoff |
| Maestro Scenario C (`tests/maestro/scenario-c.yaml`) pass | WP-13 usability + promotion readiness |
| Real-runtime app-path smoke pass on RC candidate (native claim path) | production-claim promotion windows |
| Model manager recovery journey pass (import/download -> activate -> refresh unlock) | RC readiness windows |
| Timeout/cancel recovery assertions pass (`UI-RUNTIME-001` mapping + send-capture pass) | WP-13 promotion readiness |
| Template contract availability checks pass (`TEMPLATE_UNAVAILABLE` absent for active required models) | WP-13 promotion readiness |

## CI Baseline

Primary workflow: `.github/workflows/ci.yml`

CI responsibilities:

1. Required check `unit-and-host-tests`: `python3 tools/devctl/main.py lane test ci`
2. Required check `android-lint`: `./gradlew --no-daemon :apps:mobile-android:lintDebug` (path-filtered on PRs)
3. Required check `native-build-package-check`: `./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug` + APK assertion for `lib/arm64-v8a/libpocket_llama.so` (path-filtered on PRs)
4. Required check `android-instrumented-smoke`: `:apps:mobile-android:connectedDebugAndroidTest` on hosted emulator (path-filtered on PRs)
5. Governance checks:
   - `python3 tools/devctl/main.py governance docs-drift`
   - `python3 tools/devctl/main.py governance self-test`
   - `python3 tools/devctl/main.py governance evidence-check-changed` for changed evidence notes
   - PR template/stage-close checks on pull requests
6. Artifacts upload for test reports, lint reports, and instrumentation reports

CI note:

1. Stage-2 native closure and full Maestro release lanes remain outside required hosted PR gates.
2. Hosted instrumentation smoke is included in CI only as emulator validation and does not replace hardware closure evidence.
3. Promotion decisions must include latest QA/device evidence links.

## Nightly Automation

1. `.github/workflows/nightly-validation.yml` runs:
   - emulator instrumentation smoke matrix (`api-level: 30, 34`)
   - non-blocking Maestro smoke on hosted emulator
2. `.github/workflows/nightly-hardware-lane.yml` targets self-hosted hardware runner labels (`self-hosted`, `pocketgpt-android`) for:
   - scheduled Stage-2 quick lane
   - manual Stage-2 closure/quick dispatch
3. Hardware lane requires attached authorized device and side-load model path env vars.

## UI Test Lanes

1. Compose/ViewModel JVM tests: `./gradlew :apps:mobile-android:testDebugUnitTest`
2. Instrumentation smoke + UI assertions: `python3 tools/devctl/main.py lane android-instrumented`
3. Maestro E2E UI scenarios:
   - `tests/maestro/scenario-a.yaml`
   - `tests/maestro/scenario-b.yaml`
   - `tests/maestro/scenario-c.yaml`
   - `tests/maestro/scenario-activation-send-smoke.yaml`
4. Real-runtime app-path instrumentation (release candidates only):
   - `apps/mobile-android/src/androidTest/kotlin/com/pocketagent/android/RealRuntimeAppPathInstrumentationTest.kt`

## Runtime Lane Optimization Rules

1. Prefer model-sweep execution that reuses a loaded model for Scenario A/B in one instrumentation invocation (`keepModelLoaded=true` path).
2. Use profile-driven Stage-2 commands (`quick` for iteration, `closure` for signoff) instead of ad-hoc env toggles.
3. Use `--install-mode auto` to skip reinstall when app/test APK hashes are unchanged.
4. Use `--resume` for interrupted runs; runner uses a per-run manifest to skip already completed model/scenario sweeps.
5. Keep Stage-2 env contracts stable (`POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH`, `POCKETGPT_QWEN_3_5_2B_Q4_SIDELOAD_PATH`) across quick and closure profiles.
6. Enable prefix cache for quick iteration lanes (`POCKETGPT_PREFIX_CACHE_ENABLED=1`) and track `prefix_cache_hits/misses` + `warm_vs_cold_first_token_delta_ms`.
7. Treat closure lane as strict-only: requires `--models both --scenarios both`, strict thresholds, and runtime evidence validator PASS.
8. Real-runtime lane preflight must reuse unchanged model artifacts via on-device `model-sync-v1.json` cache while still running provisioning probe each invocation.
9. `POCKETGPT_FORCE_MODEL_SYNC=1` is reserved for debugging cache mismatches and should not be enabled in routine QA runs.

## Automation Boundary (CI vs Human)

Automate by default:

1. Unit/module tests for touched modules
2. Contract/failure-path tests for runtime wiring/policies
3. UI state/wiring tests and deterministic UI flow assertions
4. Deterministic script execution and artifact generation
5. Crash/OOM signal scanning from collected logs

Human-required checkpoints:

1. USB trust bootstrap (developer mode + RSA authorization)
2. Physical environment control during benchmark/soak windows
3. Anomaly adjudication when metrics/logs conflict
4. Final UX quality call when behavior passes tests but usability concerns remain

## Artifact/Evidence Policy

1. Raw machine artifacts only in `scripts/benchmarks/runs/YYYY-MM-DD/<device>/...`
2. Human evidence notes/status only in `docs/operations/evidence/wp-xx/...`
3. Stage closure requires evidence note linking raw artifact paths
4. WP-11 closure requires both:
   - raw UI run outputs (instrumentation/Maestro/device lane)
   - product-readable UX acceptance note (`docs/operations/evidence/wp-11/...`)

## Android Automation Lanes

1. Espresso instrumentation lane: `python3 tools/devctl/main.py lane android-instrumented`
2. Maestro E2E lane: `python3 tools/devctl/main.py lane maestro`
3. Real-runtime user journey gate (aggregated):
   - strict SLA: `python3 tools/devctl/main.py lane journey --repeats 1 --mode strict --reply-timeout-seconds 90`
   - valid-output reliability (slow devices): `python3 tools/devctl/main.py lane journey --repeats 1 --mode valid-output --steps instrumentation,send-capture`
4. Release promotion requires latest PASS ids for both real-runtime instrumentation journey and real-runtime Maestro journey.
5. Journey send-capture pass requires terminal proof (`terminal_event_seen=true`) and non-empty response; strict mode additionally enforces SLA-oriented timing checks.

## Lane Selection Policy

1. `fast` and `auto` rely on changed-file rules from `config/devctl/test-selection.yaml`.
2. Any change under `packages/native-bridge/**` or `apps/mobile-android/src/main/cpp/**` must trigger Stage-2 quick lane before merge.
3. Any UI layer change under `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/**` must trigger Android instrumented smoke lane before merge.
4. Closure evidence is only valid from Stage-2 closure profile artifacts generated in the same run directory and date window.
5. Merge readiness requires both engineering correctness and user-facing quality validation.
