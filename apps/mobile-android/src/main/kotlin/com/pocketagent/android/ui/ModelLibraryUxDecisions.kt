package com.pocketagent.android.ui

import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.runtime.RuntimeLoadedModel

internal enum class DownloadedModelBadge {
    LOADED,
    SWITCHING,
    DEFAULT,
    ACTIVE,
    READY,
}

internal data class RemoveVersionPlan(
    val requiresOffload: Boolean,
    val requiresClearingActiveSelection: Boolean,
    val isBlockedByActiveSelection: Boolean,
)

internal fun resolveDownloadedModelBadge(
    model: ProvisionedModelState,
    version: ModelVersionDescriptor,
    defaultGetReadyModelId: String?,
    activeModel: RuntimeLoadedModel?,
    loadedModel: RuntimeLoadedModel?,
): DownloadedModelBadge {
    val isLoaded = loadedModel?.modelId == model.modelId && loadedModel.modelVersion == version.version
    if (isLoaded) {
        return DownloadedModelBadge.LOADED
    }
    val isRequested = activeModel?.modelId == model.modelId &&
        activeModel.modelVersion == version.version &&
        loadedModel != activeModel
    if (isRequested) {
        return DownloadedModelBadge.SWITCHING
    }
    val isActive = version.isActive || model.activeVersion == version.version
    if (isActive && model.modelId == defaultGetReadyModelId) {
        return DownloadedModelBadge.DEFAULT
    }
    if (isActive) {
        return DownloadedModelBadge.ACTIVE
    }
    return DownloadedModelBadge.READY
}

internal fun resolveRemoveVersionPlan(
    model: ProvisionedModelState,
    version: ModelVersionDescriptor,
    loadedModel: RuntimeLoadedModel?,
): RemoveVersionPlan {
    val isLoaded = loadedModel?.modelId == model.modelId && loadedModel.modelVersion == version.version
    val isActive = version.isActive || model.activeVersion == version.version
    val isOnlyInstalledVersion = model.installedVersions.size <= 1
    val canClearActiveSelection = isActive && isOnlyInstalledVersion
    return RemoveVersionPlan(
        requiresOffload = isLoaded,
        requiresClearingActiveSelection = canClearActiveSelection,
        isBlockedByActiveSelection = isActive && !canClearActiveSelection,
    )
}
