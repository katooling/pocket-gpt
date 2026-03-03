# Android Host Runtime Lane

Host/JVM execution lane for fast smoke iteration and deterministic contract tests.

Use:

```bash
./gradlew --no-daemon :apps:mobile-android-host:run
./gradlew --no-daemon :apps:mobile-android-host:test
```

This module compiles the shared runtime/container sources from `apps/mobile-android/src/main/kotlin` and excludes Android UI entrypoints.
