# Engineering Playbook

Last updated: 2026-03-05

## Mission

Ship stable runtime and UX infrastructure while preserving privacy/safety contracts and reproducible lane behavior.

## Current Operating Truth

1. WP-11 and WP-12 are closed with evidence.
2. WP-13 is open due moderated UX evidence gap.
3. Current engineering release-critical item is lane robustness (`ENG-19`).

## Done

- [x] `ENG-01` through `ENG-18` core MVP and hardening tickets
- [x] WP-12 backend/runtime closure and reruns
- [x] P1 model manager phase-2 and recovery UX wiring

## In Progress

- [ ] `ENG-19` preflight robustness for busy media path cases (retry/fallback behavior landed, rerun packet pending)
- [ ] `ENG-20` runtime cancel/timeout contract hardening across app-runtime/native bridge + telemetry contract
- [ ] DX-01 layered test profile rollout
- [ ] DX-02 provider-style cache rollout
- [ ] WP-09 stabilization support

## Ready Queue

- [ ] Support `QA-11` full lane rerun and pass-id publication
- [ ] Support `QA-WP13-RUN02` moderated run instrumentation/debug readiness

## Task Queue

| Task ID | Task | Status | Owner | References |
|---|---|---|---|---|
| ENG-19 | Devctl preflight robustness (busy media path retry/fallback + deterministic behavior) | In Progress | Eng Platform | `tools/devctl/lanes.py`, `tools/devctl/tests/test_lanes.py`, `docs/testing/android-dx-and-test-playbook.md` |
| ENG-20 | Runtime cancel/timeout contract hardening (`MvpRuntimeFacade` -> orchestrator -> JNI/fallback bridge) + journey telemetry parity | In Progress | Runtime Eng + Platform Eng | `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/MvpRuntimeFacade.kt`, `packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime/RuntimeOrchestrator.kt`, `packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/RuntimeBridgeContracts.kt`, `docs/operations/eng-20-runtime-cancel-timeout-contract.md` |
| DX-01 | Layered test profiles + Stage-2 quick/closure efficiency automation | In Progress | Eng Platform + Runtime | `docs/testing/test-lane-profiles-and-selection.md`, `scripts/dev/README.md` |
| DX-02 | Provider-style caching rollout + telemetry | In Progress | Runtime + Platform | `docs/testing/stage-2-benchmark-runbook.md` |
| ENG-09 | STT/TTS technical spikes (post-MVP) | Backlog | Runtime Eng | `docs/roadmap/product-roadmap.md` |

## Engineering Decision Rule

1. Preflight behavior must avoid false-negative hard failures from transient/busy storage states.
2. Lane changes require unit coverage and rerun evidence on target device.
3. Cancel/timeout behavior must be deterministic in UI and documented for JNI + fallback runtime paths.
4. Release-claim impacting code changes must include updated docs and evidence pointers.

## Engineering References

- `docs/operations/execution-board.md`
- `docs/roadmap/mvp-implementation-tracker.md`
- `docs/testing/test-strategy.md`
- `docs/testing/android-dx-and-test-playbook.md`
- `docs/operations/eng-20-runtime-cancel-timeout-contract.md`
