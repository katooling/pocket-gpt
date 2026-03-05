# Test Lane Profiles and Selection

Last updated: 2026-03-05

## Purpose

Define deterministic rules for which test lane/profile to run, with changed-file-based defaults for development velocity.

## Canonical Commands

```bash
bash scripts/dev/test.sh [fast|core|merge|auto|full|quick|ci]
bash scripts/dev/bench.sh stage2 --profile <quick|closure> --device <id> [--models 0.8b|2b|both] [--scenarios a|b|both] [--resume] [--install-mode auto|force|skip] [--logcat filtered|full]
```

## Profile Matrix

| Profile | Goal | Scope | Clean | Android Unit | Selection Basis |
|---|---|---|---|---|---|
| `fast` | shortest safe feedback loop | changed-file mapped tasks | no | only when mapped | `config/devctl/test-selection.yaml` |
| `auto` | changed-file adaptive lane | changed-file mapped tasks + mapped Android unit | no | conditional by rule | `config/devctl/test-selection.yaml` |
| `core` | full module safety net | all module tasks + Android unit | no | yes (if SDK configured) | fixed |
| `merge` | PR/CI gate | clean + full module tasks + Android unit | yes | yes (if SDK configured) | fixed |
| `quick` | compatibility alias | same as `core` | no | yes | alias |
| `full` | compatibility alias | same as `merge` | yes | yes | alias |
| `ci` | compatibility alias | same as `merge` | yes | yes | alias |

## Changed-File Rules (Selection)

Source of truth: `config/devctl/test-selection.yaml`

Key mappings:

1. `packages/app-runtime/**` -> `:packages:app-runtime:test`
2. `packages/native-bridge/**` -> `:packages:native-bridge:test`
3. `packages/memory/**` -> `:packages:memory:test`
4. `packages/core-domain/**` -> `:packages:core-domain:test`
5. `apps/mobile-android/src/main/cpp/**` -> native-bridge tests + Stage-2 quick recommendation
6. `apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/**` -> Android unit + instrumentation recommendation

`fast`/`auto` writes follow-up lane guidance to:

- `build/devctl/recommended-lanes.txt`

## Stage-2 Profiles

### `quick`

1. Iteration-focused profile.
2. Default model set is `0.8b`.
3. Supports partial model/scenario execution and resume.
4. Uses paired cold/warm sampling (default `runs=2`) and emits `warm_vs_cold_first_token_delta_ms`.
5. Runtime validator report is generated but not enforced as closure gate.

### `closure`

1. Signoff profile.
2. Requires `--models both --scenarios both`.
3. Strict threshold mode enforced.
4. Runtime validator PASS required.

## Decision Tree

1. Changed Kotlin/C++ runtime or bridge code?
   - Run `bash scripts/dev/test.sh fast`
   - Then run Stage-2 quick.
2. Changed UI layer?
   - Run `bash scripts/dev/test.sh fast`
   - Then run Android instrumented lane.
3. Preparing PR merge gate?
   - Run `bash scripts/dev/test.sh merge`.
4. Preparing closure/signoff evidence?
   - Run Stage-2 closure and publish evidence draft + dated ops note.

## Artifacts and Caching

1. Lane recommendations: `build/devctl/recommended-lanes.txt`
2. Stage-2 run manifest (resume): `scripts/benchmarks/runs/YYYY-MM-DD/<device>/stage2-run-manifest.tsv`
3. Stage-2 metadata: `scripts/benchmarks/runs/YYYY-MM-DD/<device>/stage2-run-meta.env`
4. APK install cache: `scripts/benchmarks/cache/<device>/apk-install-state.env`
5. Model provision cache: `scripts/benchmarks/cache/<device>/model-provision-state.env`
6. Device env exports: `scripts/benchmarks/device-env/<device>.env`
7. Stage-2 cache counters: `scripts/benchmarks/runs/YYYY-MM-DD/<device>/stage2-run-meta.env`
8. Journey gate reports: `scripts/benchmarks/runs/YYYY-MM-DD/<device>/journey/<stamp>/journey-report.json`

## Hosted CI Mapping

1. Required GitHub CI checks:
   - `unit-and-host-tests`
   - `android-lint`
   - `native-build-package-check`
   - `android-instrumented-smoke` (required when Android/runtime paths change on PRs)
2. `android-lint`, `native-build-package-check`, and `android-instrumented-smoke` are path-filtered on pull requests to avoid running heavy Android jobs for docs-only changes.
3. Stage-2 closure and full Maestro flows remain outside required hosted PR gates and stay in nightly/manual hardware cadence.
