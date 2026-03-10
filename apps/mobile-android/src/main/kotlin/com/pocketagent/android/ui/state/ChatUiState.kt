package com.pocketagent.android.ui.state

import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.core.RoutingMode
import com.pocketagent.runtime.RuntimePerformanceProfile

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
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
    val requestId: String? = null,
    val finishReason: String? = null,
    val terminalEventSeen: Boolean = false,
    val interaction: PersistedInteractionMessage? = null,
)

data class PersistedInteractionMessage(
    val role: String,
    val parts: List<PersistedInteractionPart> = emptyList(),
    val toolCalls: List<PersistedToolCall> = emptyList(),
    val toolCallId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class PersistedInteractionPart(
    val type: String,
    val text: String? = null,
)

data class PersistedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val status: PersistedToolCallStatus = PersistedToolCallStatus.PENDING,
)

enum class PersistedToolCallStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

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
    val performanceProfile: RuntimePerformanceProfile = RuntimePerformanceProfile.BALANCED,
    val keepAlivePreference: RuntimeKeepAlivePreference = RuntimeKeepAlivePreference.AUTO,
    val gpuAccelerationEnabled: Boolean = false,
    val gpuAccelerationSupported: Boolean = false,
    val gpuProbeStatus: GpuProbeStatus = GpuProbeStatus.PENDING,
    val gpuProbeFailureReason: String? = null,
    val gpuMaxQualifiedLayers: Int = 0,
    val runtimeBackend: String? = null,
    val backendProfile: String? = null,
    val compiledBackend: String? = null,
    val nativeRuntimeSupported: Boolean? = null,
    val strictAcceleratorFailFast: Boolean? = null,
    val autoBackendCpuFallback: Boolean? = null,
    val startupProbeState: StartupProbeState = StartupProbeState.IDLE,
    val modelRuntimeStatus: ModelRuntimeStatus = ModelRuntimeStatus.NOT_READY,
    val modelStatusDetail: String? = null,
    val activeModelId: String? = null,
    val lastFirstTokenLatencyMs: Long? = null,
    val lastTotalLatencyMs: Long? = null,
    val lastPrefillMs: Long? = null,
    val lastDecodeMs: Long? = null,
    val lastTokensPerSec: Double? = null,
    val lastPeakRssMb: Double? = null,
    val startupChecks: List<String> = emptyList(),
    val startupWarnings: List<String> = emptyList(),
    val lastErrorCode: String? = null,
    val lastErrorUserMessage: String? = null,
    val lastErrorTechnicalDetail: String? = null,
    val lastError: String? = null,
    val sendElapsedMs: Long? = null,
    val sendSlowState: String? = null,
)

enum class RuntimeKeepAlivePreference {
    AUTO,
    ALWAYS,
    ONE_MINUTE,
    FIVE_MINUTES,
    FIFTEEN_MINUTES,
    UNLOAD_IMMEDIATELY,
}

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
    DEGRADED,
    BLOCKED_TIMEOUT,
    BLOCKED,
}

enum class FirstSessionStage {
    ONBOARDING,
    GET_READY,
    READY_TO_CHAT,
    FIRST_ANSWER_DONE,
    FOLLOW_UP_DONE,
    ADVANCED_UNLOCKED,
}

data class FirstSessionTelemetryEvent(
    val eventName: String,
    val eventTimeUtc: String,
)

data class ChatUiState(
    val sessions: List<ChatSessionUiModel> = emptyList(),
    val activeSessionId: String? = null,
    val composer: ComposerUiState = ComposerUiState(),
    val runtime: RuntimeUiState = RuntimeUiState(),
    val isSessionDrawerOpen: Boolean = false,
    val isAdvancedSheetOpen: Boolean = false,
    val isToolDialogOpen: Boolean = false,
    val isPrivacySheetOpen: Boolean = false,
    val showOnboarding: Boolean = false,
    val onboardingPage: Int = 0,
    val firstSessionStage: FirstSessionStage = FirstSessionStage.ONBOARDING,
    val advancedUnlocked: Boolean = true,
    val firstAnswerCompleted: Boolean = false,
    val followUpCompleted: Boolean = false,
    val showAdvancedUnlockCue: Boolean = false,
    val firstSessionTelemetryEvents: List<FirstSessionTelemetryEvent> = emptyList(),
) {
    val activeSession: ChatSessionUiModel?
        get() = sessions.firstOrNull { it.id == activeSessionId }
}
