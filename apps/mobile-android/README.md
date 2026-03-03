# Android MVP Module

Android-first MVP implementation scaffolding aligned with the staged execution plan.

## Implemented Stage Coverage

1. Stage 1: text-only streaming chat loop via `AndroidMvpContainer`
2. Stage 2: model artifact registration and benchmark runner (`StageBenchmarkRunner`)
3. Stage 3: adaptive routing + policy + diagnostics export
4. Stage 4: local tool runtime integration
5. Stage 5: image analysis path and memory integration
6. Stage 6: resilience guards for battery/thermal/prompt limits

## Key Files

- `src/main/kotlin/com/pocketagent/android/AndroidMvpContainer.kt`
- `src/main/kotlin/com/pocketagent/android/StageBenchmarkRunner.kt`
- `src/main/kotlin/com/pocketagent/android/ResilienceGuards.kt`
- `src/main/kotlin/com/pocketagent/android/StageRunnerMain.kt`

## Android Device Testing

Use the playbook:

- `docs/testing/android-dx-and-test-playbook.md`

Use scripts:

- `scripts/android/run_stage_checks.sh`
- `scripts/android/collect_logcat.sh`
