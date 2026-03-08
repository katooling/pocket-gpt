# Model Management and Runtime Readiness Flow

Last updated: 2026-03-08
Owner: Runtime + Android
Lifecycle: Phase-2 implemented (versioned install + downloads + activation control)

## Product Defaults (P1)

1. Download channel is enabled by default in the primary app build.
2. Version activation auto-activates only when no active version exists for that model.
3. Active version deletion is blocked; safe cleanup is enforced for temp/failed artifacts.
4. Download continuation/recovery uses persisted task state + WorkManager.
5. Provisioning registry is `modelId` keyed and supports baseline + dynamically discovered IDs.

## Runtime Controls Defaults

1. Runtime performance profile default: `BALANCED`.
2. Exposed profiles: `BATTERY`, `BALANCED`, `FAST`.
3. Routing default: `AUTO`.
4. GPU toggle is capability-gated and persisted only when supported.
5. Model residency defaults:
   - keep loaded in foreground: enabled
   - idle unload TTL: `10m`
   - warmup on startup: enabled
6. Runtime detail labels after generation:
   - first-token latency
   - total latency
   - prefill latency
   - decode latency
   - decode rate

## Runtime Status Model

Runtime status shown in-app:

1. `Not ready`: required startup constraints not satisfied.
2. `Loading`: runtime/model load or stream in progress.
3. `Ready`: runtime can serve send/tool/image paths.
4. `Error`: startup/runtime failure requiring user action.

Runtime backend labels:

1. `NATIVE_JNI`
2. `ADB_FALLBACK`
3. `UNAVAILABLE`

Send unlock rule:

1. Send path requires startup probe + runtime status both `Ready`.
2. Optional-model-only startup failures may still resolve to `Ready` with warnings.
3. Startup timeout remains blocked until refresh (deterministic timeout guidance).

Provisioning readiness (`RuntimeProvisioningSnapshot`) is separate:

1. `READY`: all startup-candidate models provisioned.
2. `DEGRADED`: at least one verified active model exists, but optional models are missing.
3. `BLOCKED`: no verified active startup-candidate model exists.

## In-App Provisioning Paths

### Simple-first `Get ready` action

1. Refresh manifest.
2. Pick default `Qwen 3.5 0.8B (Q4)` version.
3. Enqueue download.
4. On completion, activate if needed and refresh runtime checks.
5. If manifest/download is unavailable, import path remains available.

### A) Local import

1. Open `Advanced` -> `Open model setup`.
2. Import at least one required GGUF model (recommended first: `Qwen 3.5 0.8B (Q4)`).
3. App records versioned metadata:
   - absolute path
   - SHA-256
   - provenance issuer/signature metadata
   - runtime compatibility
4. Imported version auto-activates only when no active version exists.
5. Refresh runtime checks.

### B) Download manager

1. Open model setup and refresh manifest.
2. Start download for model/version.
3. Task states: `Queued -> Downloading -> Verifying -> InstalledInactive/Completed`.
4. Checksum and runtime compatibility are hard gates.
5. Provenance metadata is retained; policy enforcement is determined by version verification policy (`INTEGRITY_ONLY` or `PROVENANCE_STRICT`).
6. Pause/resume/retry/cancel are supported in-app.

## Verification and Failure Rules

1. Zero-active-model state stays blocked until provisioning + refresh checks succeed.
2. Checksum/runtime mismatch never installs a version.
3. Duplicate active non-terminal enqueue returns existing task ID.
4. One active task per model/version.
5. Active version cannot be removed until another version is activated.
6. Bundled catalog fallback remains available when remote fetch fails.

## Manifest Outage Fallback

1. Manifest fetch failure or empty response must never hide import flow.
2. Download path is marked degraded until manifest recovers.
3. If a verified active model already exists, runtime remains usable after refresh.
4. Recovery guidance routes to import, retry manifest, or refresh checks.

## Stage-2 Side-Load Path (Bench/Closure)

1. `scripts/android/provision_sideload_models.sh`
2. `bash scripts/dev/bench.sh stage2 ...`
3. `python3 scripts/benchmarks/validate_stage2_runtime_evidence.py ...`
