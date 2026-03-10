# Module Boundaries

This document defines ownership and cross-layer rules for the Android app/runtime code.

## Ownership

1. `com.pocketagent.android.ui`: UI state, view models, composables, UI controllers.
2. `com.pocketagent.android.runtime`: runtime/provisioning gateways, transport, and device/runtime integration.
3. `com.pocketagent.android`: composition root and app bootstrap.
4. `packages/*`: domain/runtime/tooling libraries shared across entrypoints.

## No-Cross-Layer Rules

1. UI code must not reference `AppRuntimeDependencies` directly.
2. `AppRuntimeDependencies` may be referenced only by sanctioned boundary adapters:
   `AppDependencies.kt`, `RuntimeBootstrapper.kt`, `ProvisioningGateway.kt`.
3. Runtime integration (`com.pocketagent.android.runtime`) must not depend on UI packages.
4. Tests should avoid wall-clock sleeps for deterministic coroutine time control.

## Enforcement

1. `ArchitectureBoundaryTest` enforces boundary references in unit tests.
2. Runtime/provisioning interfaces (`RuntimeGateway`, `ProvisioningGateway`) are the only supported dependency seams from UI.

