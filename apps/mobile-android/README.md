# Android App Module

Real Android app module for instrumentation-ready execution (`test` + `androidTest`).

## Source of truth

- Dev/test commands: `scripts/dev/README.md`
- Android workflow details: `docs/testing/runbooks.md`
- Runtime diagnostics and tuning debug guide: `docs/testing/runtime-tuning-debugging.md`

## Role

1. Android packaging and runtime entrypoint (`MainActivity`)
2. Compose-based chat, onboarding, advanced controls, and model provisioning UX surfaces
3. Android unit test lane (`testDebugUnitTest`)
4. Android instrumentation lane (`connectedDebugAndroidTest`)
5. Runtime readiness/startup integration with app runtime facade contracts

Host smoke/runtime loop remains in `apps/mobile-android-host`.
