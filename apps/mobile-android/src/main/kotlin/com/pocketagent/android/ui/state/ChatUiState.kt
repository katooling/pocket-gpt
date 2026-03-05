package com.pocketagent.android.ui.state

import com.pocketagent.core.RoutingMode
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

enum class MessageKind {
    TEXT,
    IMAGE,
    TOOL,
    DIAGNOSTIC,
}

data class MessageUiModel(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestampEpochMs: Long,
    val kind: MessageKind = MessageKind.TEXT,
    val imagePath: String? = null,
    val toolName: String? = null,
    val isStreaming: Boolean = false,
)

data class ChatSessionUiModel(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val messages: List<MessageUiModel>,
)

data class ComposerUiState(
    val text: String = "",
    val isSending: Boolean = false,
)

data class RuntimeUiState(
    val offlineOnly: Boolean = true,
    val routingMode: RoutingMode = RoutingMode.AUTO,
    val runtimeBackend: String? = null,
    val startupProbeState: StartupProbeState = StartupProbeState.IDLE,
    val modelRuntimeStatus: ModelRuntimeStatus = ModelRuntimeStatus.NOT_READY,
    val modelStatusDetail: String? = null,
    val activeModelId: String? = null,
    val lastFirstTokenLatencyMs: Long? = null,
    val lastTotalLatencyMs: Long? = null,
    val startupChecks: List<String> = emptyList(),
    val lastErrorCode: String? = null,
    val lastErrorUserMessage: String? = null,
    val lastErrorTechnicalDetail: String? = null,
    val lastError: String? = null,
)

enum class ModelRuntimeStatus {
    NOT_READY,
    LOADING,
    READY,
    ERROR,
}

enum class StartupProbeState {
    IDLE,
    RUNNING,
    READY,
    BLOCKED,
}

data class ChatUiState(
    val sessions: List<ChatSessionUiModel> = emptyList(),
    val activeSessionId: String? = null,
    val composer: ComposerUiState = ComposerUiState(),
    val runtime: RuntimeUiState = RuntimeUiState(),
    val modelManager: ModelManagerUiState = ModelManagerUiState(),
    val isSessionDrawerOpen: Boolean = false,
    val isAdvancedSheetOpen: Boolean = false,
    val isToolDialogOpen: Boolean = false,
    val isPrivacySheetOpen: Boolean = false,
    val showOnboarding: Boolean = false,
    val onboardingPage: Int = 0,
) {
    val activeSession: ChatSessionUiModel?
        get() = sessions.firstOrNull { it.id == activeSessionId }
}

data class ModelManagerUiState(
    val downloads: List<DownloadTaskState> = emptyList(),
    val storageSummary: StorageSummary? = null,
    val installedVersionsByModelId: Map<String, List<ModelVersionDescriptor>> = emptyMap(),
    val activeVersionByModelId: Map<String, String> = emptyMap(),
)
