package com.pocketagent.android.ui

import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.runtime.ModelRegistry

internal fun resolveModelRuntimeProfile(isDebugBuild: Boolean): ModelRuntimeProfile {
    return if (isDebugBuild) {
        ModelRuntimeProfile.DEV_FAST
    } else {
        ModelRuntimeProfile.PROD
    }
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
    return manifest.models
        .firstOrNull { model -> model.modelId == defaultModelId }
        ?.versions
        ?.firstOrNull()
}
