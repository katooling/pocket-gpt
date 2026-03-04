# Android App Module

Real Android app module for instrumentation-ready execution (`test` + `androidTest`).

## Source of truth

- Dev/test commands: `scripts/dev/README.md`
- Android workflow details: `docs/testing/android-dx-and-test-playbook.md`

## Role

1. Android packaging and runtime entrypoint (`MainActivity`)
2. Compose-based MVP chat UI surface (WP-11)
3. Android unit test lane (`testDebugUnitTest`)
4. Android instrumentation lane (`connectedDebugAndroidTest`)

Host smoke/runtime loop remains in `apps/mobile-android-host`.
