package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.runtime.RuntimeLoadedModel

internal data class ChatHeaderUiState(
    val activeRuntimeModelLabel: String?,
    val lastUsedModelLabel: String?,
    val canLoadLastUsedModel: Boolean,
)

internal fun deriveChatHeaderUiState(modelLoadingState: ModelLoadingState): ChatHeaderUiState {
    val activeRuntimeModelLabel = modelLoadingState.loadedModel?.toDisplayLabel()
    val lastUsedModelLabel = modelLoadingState.lastUsedModel?.toDisplayLabel()
    val canLoadLastUsedModel = modelLoadingState.loadedModel == null &&
        modelLoadingState.lastUsedModel != null &&
        modelLoadingState !is ModelLoadingState.Loading &&
        modelLoadingState !is ModelLoadingState.Offloading &&
        modelLoadingState !is ModelLoadingState.Error
    return ChatHeaderUiState(
        activeRuntimeModelLabel = activeRuntimeModelLabel,
        lastUsedModelLabel = lastUsedModelLabel,
        canLoadLastUsedModel = canLoadLastUsedModel,
    )
}

private fun RuntimeLoadedModel.toDisplayLabel(): String {
    return "${modelId} ${modelVersion.orEmpty()}".trim()
}
