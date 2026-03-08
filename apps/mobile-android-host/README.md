# Android Host Runtime Lane

Host/JVM execution lane for fast smoke iteration and deterministic contract tests.

Use:

```bash
./gradlew --no-daemon :apps:mobile-android-host:run
./gradlew --no-daemon :apps:mobile-android-host:test
```

This module runs runtime scaffolding from `packages/app-runtime` + `packages/native-bridge` without Android UI dependencies.
