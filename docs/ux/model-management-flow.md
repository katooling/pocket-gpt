# Model Management and Runtime Readiness Flow

Last updated: 2026-03-05
Owner: Runtime + Android
Status: Phase-2 implemented (versioned install + downloads + activation control)

## Product Defaults (P1)

1. Download channel: enabled by default in the primary app build.
2. Version activation: manual switch (new downloads stay inactive until user activates).
3. Storage cleanup: guided safe (active version delete blocked, failed/temp artifacts handled by manager flows).
4. Lifecycle on close/background/reopen: WorkManager-backed background continuation with persisted task state.

## Runtime Status Model

Runtime status is shown in-app as:

1. `Not ready` - required model artifacts are missing or unverified.
2. `Loading` - runtime/model load in progress.
3. `Ready` - runtime available for inference/tool paths.
4. `Error` - startup or runtime failure state.

Runtime backend identity is surfaced as:

1. `NATIVE_JNI`
2. `ADB_FALLBACK`
3. `UNAVAILABLE`

Composer/image actions remain locked until startup checks pass and runtime state is `Ready`.

## In-App Provisioning Paths

### A) Local import (all builds)

1. Open `Advanced` -> `Open model setup`.
2. Import both required GGUF files:
   - `Qwen 3.5 0.8B (Q4)`
   - `Qwen 3.5 2B (Q4)`
3. App copies files into private storage and records versioned metadata:
   - absolute path
   - SHA-256
   - provenance issuer/signature metadata
   - runtime compatibility
4. Imported version becomes active by default for that model.
5. Tap `Refresh runtime checks`; if both required active versions verify, runtime moves to `Ready`.

### B) Download manager

1. Open model setup and refresh manifest.
2. Start download for selected model/version.
3. Task enters `Queued -> Downloading -> Verifying`.
4. Verification pipeline enforces checksum/provenance/runtime compatibility checks.
5. On pass: version is recorded as `InstalledInactive`.
6. User activates selected version.
7. User refreshes runtime checks to unlock runtime for chat/image actions.

## Journey State Contract (Forward/Back/Close/Pause/Load/Unlock)

1. `NotReady` -> open setup -> `SetupOpen`
2. `SetupOpen` -> start download -> `Queued/Downloading`
3. `Downloading` -> pause -> `Paused`
4. `Downloading` -> back/close/home -> background download continues
5. App kill/reopen -> task restored from persisted store + WorkManager
6. `Verifying` pass -> `InstalledInactive`
7. Set active version + refresh checks -> `ReadyUnlocked` or back to `NotReady` with specific reason

## Verification and Failure Rules

1. Runtime startup remains strict: missing/invalid artifacts block unlock.
2. Checksum/provenance/runtime mismatch never marks a version as installed.
3. Duplicate enqueue of active non-terminal task returns existing task id.
4. One active download task per model/version; retries use persisted task state.
5. Active version cannot be removed until another version is activated.

## Network Policy

1. `src/main/AndroidManifest.xml` includes INTERNET permission for model distribution.
2. Cleartext remains disabled via `network_security_config.xml`.

## Manifest Outage Fallback

1. Manifest fetch failure or empty manifest response must not hide or disable import flow.
2. Download channel is marked degraded until manifest is recoverable.
3. If verified active models already exist, runtime remains usable after refresh checks.
4. Recovery guidance must route user to one of:
   - import local model files,
   - retry manifest/download,
   - refresh runtime checks.

## Stage-2 Side-Load Path (Bench/Closure)

Side-load remains available for benchmark/closure:

1. `scripts/android/provision_sideload_models.sh`
2. `bash scripts/dev/bench.sh stage2 ...`
3. `python3 scripts/benchmarks/validate_stage2_runtime_evidence.py ...`
