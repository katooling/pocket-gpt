package com.pocketagent.android.ui

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ModelRegistry

internal fun resolveModelRuntimeProfile(isDebugBuild: Boolean): ModelRuntimeProfile {
    // Keep app-path startup policy aligned with product defaults in all app builds.
    // Dedicated benchmark/stage lanes can still opt into DEV_FAST via runtime-level config.
    return ModelRuntimeProfile.PROD
}

internal fun resolveDefaultGetReadyModelId(
    isDebugBuild: Boolean,
    modelRegistry: ModelRegistry = ModelRegistry.default(),
): String? {
    val profile = resolveModelRuntimeProfile(isDebugBuild = isDebugBuild)
    return modelRegistry.defaultGetReadyModelId(profile = profile)
}

internal fun resolveDefaultGetReadyVersion(
    manifest: ModelDistributionManifest,
    defaultModelId: String?,
): ModelDistributionVersion? {
    if (defaultModelId.isNullOrBlank()) {
        return null
    }
    val versions = manifest.models
        .firstOrNull { model -> model.modelId == defaultModelId }
        ?.versions
        ?: return null
    return versions.minWithOrNull(
        compareBy<ModelDistributionVersion>(
            { defaultGetReadyVersionPriority(it.version) },
            { it.fileSizeBytes.takeIf { size -> size > 0L } ?: Long.MAX_VALUE },
            { it.version },
        ),
    )
}

private fun defaultGetReadyVersionPriority(version: String): Int {
    val normalized = version.trim().lowercase()
    return when {
        normalized == "q4_0" -> 0
        normalized.startsWith("q4") -> 1
        normalized.contains("q4") -> 2
        normalized.contains("iq") -> 4
        else -> 3
    }
}

internal fun supportedRoutingModes(): List<RoutingMode> {
    return RoutingMode.entries.filter { mode ->
        if (mode == RoutingMode.AUTO) {
            return@filter true
        }
        val modelId = ModelCatalog.modelIdForRoutingMode(mode) ?: return@filter false
        ModelCatalog.descriptorFor(modelId)?.bridgeSupported == true
    }
}

internal fun coerceSupportedRoutingMode(mode: RoutingMode): RoutingMode {
    return if (supportedRoutingModes().contains(mode)) {
        mode
    } else {
        RoutingMode.AUTO
    }
}
