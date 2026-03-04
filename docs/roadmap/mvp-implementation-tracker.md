# MVP Implementation Tracker (Android-First)

Use this tracker to execute the six MVP stages against explicit entry/exit criteria.

## Stage Status

| Stage | Description | Primary Owner | Scaffolded in Repo | Validated on Device | Required Evidence |
|---|---|---|---|---|---|
| 1 | Android text-only runtime slice with `llama.cpp` bridge wiring | Runtime + Android | Yes | Yes | startup check output, 10-run short chat logs |
| 2 | Qwen 0.8B swap + scenario A/B thresholds | Runtime + QA | Yes | Yes | benchmark CSV + threshold report |
| 3 | Routing/policy/observability integration | Runtime + Security | Yes | Yes | downgrade test logs, diagnostics export |
| 4 | Schema-safe tool runtime v1 | Platform + Security | Yes | Yes | malformed call rejection tests |
| 5 | Memory v1 + image input v1 | Core/AI + Runtime | Yes | Yes | scenario C benchmark + quality rubric notes |
| 6 | Hardening + privacy + beta packet | Platform + QA + Product | Done (WP-07) | Yes | soak test logs, go/no-go packet |

## Cross-Track Package Status

| Package | Description | Owner | Status | Gate Rule |
|---|---|---|---|---|
| WP-07 | Beta hardening + go/no-go packet | QA + Product + Eng | Done | Must close for external beta |
| WP-11 | Android MVP user experience (chat/session/image/tool UX) | Engineering + Product + QA | Done | Must close for external beta |
| WP-12 | Backend production runtime closure (native/runtime/data-plane hardening) | Engineering + QA + Product + Security | In Progress | Must close before production-claim hardening signoff |

External beta signoff policy:

1. `WP-07` must be `Done`.
2. `WP-11` must be `Done`.
3. `PROD-03` signoff must be stamped by Product, QA, and Engineering.

## Stage-wide Quality Gates (apply to every stage)

- [ ] CI green on unit tests for touched modules.
- [ ] Stage evidence artifacts committed/attached in expected paths:
  - raw machine artifacts in `scripts/benchmarks/runs/...`
  - human evidence notes in `docs/operations/evidence/...`
- [ ] Regression checks run for previously completed stages.
- [ ] `docs/roadmap/next-steps-execution-plan.md` updated with current status/date.
- [ ] CI lane + CLI phone lane workflow kept current in `docs/testing/just-cli-android-validation-plan.md`.
- [ ] Risk register reviewed for newly introduced risks.

## Stage 1 Checklist

- [x] Build and run Stage runner with `llama.cpp` bridge
- [x] Device trust setup complete (`adb` visible, USB debugging enabled, RSA authorized)
- [x] Capture first-token and total latency
- [x] Confirm no crashes/OOM in 10 short runs
- [x] 10-run loop summary captured via `scripts/android/run_short_loop.sh`
- [x] host/JVM and Android unit lanes pass for stage changes (`:apps:mobile-android-host:test`, `:apps:mobile-android:testDebugUnitTest`)
- [x] Fast local run script and expected runtime bridge behavior documented

## Stage 2 Checklist

- [x] Artifact-validation startup path active for Stage-2 closure (`POCKETGPT_QWEN_3_5_0_8B_Q4_SHA256`, `POCKETGPT_QWEN_3_5_2B_Q4_SHA256`)
- [x] Scenario A/B executed
- [x] Threshold pass/fail recorded
- [x] Checksum verification tests added
- [x] Threshold wrapper integrated in `scripts/dev/bench.sh stage2`
- [x] Final closure packet rerun on artifact-validated path

## Stage 3 Checklist

- [x] Validate low-battery downgrade behavior (routing matrix coverage)
- [x] Validate high-thermal downgrade behavior (routing matrix coverage)
- [x] Export diagnostics report
- [x] Routing policy boundary tests for battery/thermal/RAM/task matrix
- [x] Diagnostics redaction/no sensitive payload content validation

## Stage 4 Checklist

- [x] Positive tool tests
- [x] Malformed JSON and blocked payload tests
- [x] Allowlist enforcement verification
- [x] Schema-driven validation replaced ad-hoc parser
- [x] Security regression tests for tool payload bypass patterns

## Stage 5 Checklist

- [x] Memory retrieval relevance on follow-up prompts
- [x] Scenario C image benchmarks executed
- [x] Latency and output quality notes captured
- [x] SQLite persistence + retention/pruning validation
- [x] Deterministic image path contract tests

## Stage 6 Checklist

- [x] Run 30-minute soak test (`QA-06` PASS)
- [x] Verify resilience guards behavior
- [x] Finalize beta go/no-go packet (final signatures captured in `docs/operations/evidence/wp-07/2026-03-04-prod-03-final-signoff.md`)
- [x] Confirm release candidate checklist is fully green for WP-07 scope

## Stage 6.5 Checklist (WP-12)

- [x] Runtime backend identity (`NATIVE_JNI` vs `ADB_FALLBACK`) surfaced in stage runner output.
- [x] Startup checks block closure-path execution on fallback runtime by default.
- [x] Product decision approved for model distribution path/provenance policy (`ENG-12`): side-load/manual-internal only + strict verify-before-load contract.
- [ ] Native Android ARM `llama.cpp` compile + real inference proof packet on target device.
- [x] Model distribution strategy implemented with artifact provenance validation (`docs/operations/evidence/wp-12/2026-03-04-eng-12-model-distribution-implementation.md`).
- [x] Android-native memory backend active on runtime path (no JVM JDBC dependency) (`docs/operations/evidence/wp-12/2026-03-04-eng-14-android-native-memory.md`).
- [x] Tool data integrations remove placeholder runtime responses (`docs/operations/evidence/wp-12/2026-03-04-eng-15-tool-store-integration.md`).
- [x] Real multimodal image path replaces smoke contract on production lane (`docs/operations/evidence/wp-12/2026-03-04-eng-16-image-runtime-path.md`).
- [x] Platform network policy enforcement verified in runtime lane evidence (`docs/operations/evidence/wp-12/2026-03-04-eng-17-network-policy-wiring.md`).

Current blocker:

- `ENG-13` remains blocked on native backend proof (`NATIVE_JNI`) and full `0.8B`/`2B` + memory/PSS packet completion (`docs/operations/evidence/wp-12/2026-03-04-eng-13-native-runtime-proof.md`).
- QA closeout has been triggered and recommends holding WP-12 closure until ENG-13 is resolved (`docs/operations/evidence/wp-12/2026-03-04-qa-wp12-closeout.md`).

## WP-11 UI Gate Checklist

- [x] Add app-facing runtime façade contract (`MvpRuntimeFacade`) with stream lifecycle + routing-mode controls.
- [x] Replace placeholder `MainActivity` shell with Compose chat-first UI.
- [x] Add session list create/switch/delete UX and persisted restore path.
- [x] Add image attach + tool action UX and clear error rendering.
- [x] Add advanced controls sheet (routing override + diagnostics export + runtime details).
- [x] Add UI state + ViewModel tests (`ChatViewModelTest`) and runtime wiring tests (`AndroidMvpContainerTest` updates).
- [x] Replace placeholder Maestro assertions with real UI flow assertions.
- [x] Run QA UI acceptance suite (`UI-01` to `UI-10`) on connected device and attach evidence (`docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md`).
- [x] Re-run device instrumentation and Maestro lanes after assertion fix (`docs/operations/evidence/wp-11/2026-03-04-qa-08-ui-gate-rerun.md`).

## PROD-03 Finalization Hooks (post-WP-03)

Status: Closed on 2026-03-04. Stage 5 + Stage 6 are signed off and WP-11 acceptance evidence is complete.

- [x] Confirm execution board still shows `WP-03 = Done`.
- [x] Confirm PROD-01 workflow definition is finalized in PRD.
- [x] Confirm PROD-02 device policy table is finalized.
- [x] Confirm PROD-01 workflow evidence links are present (A/B/C + tool safety + memory persistence).
- [x] Confirm PROD-02 policy table is attached in go/no-go packet.
- [x] Confirm unresolved launch blockers list is empty or explicitly accepted by Product + QA (no remaining Product/QA gate blockers).
- [x] Stamp go/no-go packet decision date and sign-off owners.
- [x] Confirm WP-11 UI gate checklist and evidence are complete.

## Stage 5 Gate (WP-06) - Closed

Evidence:

1. `docs/operations/evidence/wp-06/2026-03-04-eng-07-closeout.md`
2. `docs/operations/evidence/wp-06/2026-03-04-eng-08.md`
3. `docs/operations/evidence/wp-06/2026-03-04-qa-05.md`

## Stage 6 Gate (WP-07) - Closed

Required signoff criteria:

1. 30-minute soak evidence attached (`QA-06` complete).
2. Resilience/startup recovery checks validated.
3. No unresolved blocker defects or explicit risk acceptance.
4. Product + QA + Engineering signatures recorded in go/no-go packet.

## UI Gate (WP-11) - Closed

Required signoff criteria:

1. UI-01..UI-10 acceptance suite executed on device lane.
2. Compose/instrumentation/Maestro lanes green for release candidate.
3. Evidence note published under `docs/operations/evidence/wp-11/` with linked raw artifacts.
4. Product confirms UX acceptance and dual-track gate closure rule is satisfied.
