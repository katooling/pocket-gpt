# Android Script Helpers

These scripts support repeatable Android device testing for MVP stages.

## Scripts

- `run_stage_checks.sh`: runs quick adb checks and prints environment state
- `collect_logcat.sh`: captures filtered logs for a package tag window

## Usage

```bash
bash scripts/android/run_stage_checks.sh
bash scripts/android/collect_logcat.sh com.pocketagent 120
```

The second parameter in `collect_logcat.sh` is capture duration in seconds.
