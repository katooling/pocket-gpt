# Model Management and Runtime Readiness Flow

Last updated: 2026-03-06
Owner: Runtime + Android
Status: Phase-2 implemented (versioned install + downloads + activation control)

## Product Defaults (P1)

1. Download channel: enabled by default in the primary app build.
2. Version activation: auto-activate only when no active version exists for that model; otherwise keep manual activation control.
3. Storage cleanup: guided safe (active version delete blocked, failed/temp artifacts handled by manager flows).
4. Lifecycle on close/background/reopen: WorkManager-backed background continuation with persisted task state.
5. Provisioning registry is `modelId`-keyed and supports baseline models plus dynamically discovered model IDs.

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

Chat send unlocks when startup checks return `Ready` or `Degraded` (for example, one optional baseline model missing or startup probe timeout on slower devices).
Image actions remain strict and require runtime state `Ready`.
Startup checks still enforce valid interaction template availability and artifact verification for active baseline models.

## In-App Provisioning Paths

### A) Local import (all builds)

1. Open `Advanced` -> `Open model setup`.
2. Import at least one required GGUF file to unlock chat quickly:
   - recommended first model: `Qwen 3.5 0.8B (Q4)`
   - optional additional model: `Qwen 3.5 2B (Q4)`
3. App copies files into private storage and records versioned metadata:
   - absolute path
   - SHA-256
   - provenance issuer/signature metadata
   - runtime compatibility
4. Imported version auto-activates only if that model has no active version yet.
5. Tap `Refresh runtime checks`; if at least one baseline active version verifies, runtime moves to `Ready` or `Degraded` and chat is available.

### B) Download manager

1. Open model setup and refresh manifest.
2. Start download for selected model/version.
3. Task enters `Queued -> Downloading -> Verifying`.
4. Verification pipeline enforces checksum/runtime compatibility as hard gates.
5. Provenance issuer/signature metadata is currently informational in app download flow (`INTEGRITY_ONLY` policy) and is retained for diagnostics/future hardening.
6. On pass:
   - `Completed` when this becomes the first active version for the model.
   - `InstalledInactive` when another active version already exists.
7. User can optionally switch active version from installed versions.
8. Runtime checks refresh updates status immediately after terminal download states.

## Journey State Contract (Forward/Back/Close/Pause/Load/Unlock)

1. `NotReady` -> open setup -> `SetupOpen`
2. `SetupOpen` -> start download -> `Queued/Downloading`
3. `Downloading` -> pause -> `Paused`
4. `Downloading` -> back/close/home -> background download continues
5. App kill/reopen -> task restored from persisted store + WorkManager
6. `Verifying` pass -> `InstalledInactive`
7. Set active version + refresh checks -> `ReadyUnlocked` or back to `NotReady` with specific reason

## Verification and Failure Rules

1. Runtime startup is strict for zero-active-model state; chat unlock is allowed in degraded mode when one baseline model is verified and active.
2. Checksum/runtime mismatch never marks a version as installed.
3. Provenance mismatch is non-blocking in current app download policy and should be surfaced as diagnostics only.
4. Duplicate enqueue of active non-terminal task returns existing task id.
5. One active download task per model/version; retries use persisted task state.
6. Active version cannot be removed until another version is activated.
7. Bundled distribution catalog is always available offline; remote catalog refresh overlays when reachable and reports sync source/error in UI.

## Migration Note

1. Prior download failures tagged `PROVENANCE_MISMATCH` were produced by the old blocking policy path.
2. After this policy update, users should retry once; successful SHA-256 verification is now the hard gate for install.

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
