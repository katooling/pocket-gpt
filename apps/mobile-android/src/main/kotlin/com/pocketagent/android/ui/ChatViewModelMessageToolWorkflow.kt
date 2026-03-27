package com.pocketagent.android.ui

import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.controllers.ToolLoopOutcome
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedInteractionMessage
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.PersistedToolCall
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun ChatViewModel.executeToolCommandInternal(
    sessionId: String,
    toolName: String,
    jsonArgs: String,
    toolCallId: String,
) {
    viewModelScope.launch(ioDispatcher) {
        when (val outcome = toolLoopUseCase.execute(toolName = toolName, jsonArgs = jsonArgs)) {
            is ToolLoopOutcome.Success -> {
                updateToolCallStatus(
                    sessionId = sessionId,
                    toolCallId = toolCallId,
                    status = PersistedToolCallStatus.COMPLETED,
                )
                val toolMessage = createMessage(
                    role = MessageRole.TOOL,
                    content = outcome.content,
                    kind = MessageKind.TOOL,
                    toolName = toolName,
                    toolCallId = toolCallId,
                )
                updateActiveSession(sessionId) { session ->
                    session.copy(
                        messages = session.messages + toolMessage,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(isSending = false, isCancelling = false),
                        runtime = state.runtime.copy(
                            runtimeBackend = runtimeFacade.runtimeBackend(),
                            modelRuntimeStatus = ModelRuntimeStatus.READY,
                            modelStatusDetail = "Local tool completed",
                        ).clearError(),
                    )
                }
                persistState()
            }
            is ToolLoopOutcome.Failure -> {
                updateToolCallStatus(
                    sessionId = sessionId,
                    toolCallId = toolCallId,
                    status = PersistedToolCallStatus.FAILED,
                )
                appendSystemMessage(
                    sessionId = sessionId,
                    content = formatUserFacingError(outcome.uiError),
                )
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(isSending = false, isCancelling = false),
                        runtime = state.runtime.copy(
                            modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                            modelStatusDetail = outcome.uiError.userMessage,
                        ).withUiError(outcome.uiError),
                    )
                }
                persistState()
            }
        }
    }
}

internal fun ChatViewModel.updateToolCallStatusInternal(
    sessionId: String,
    toolCallId: String,
    status: PersistedToolCallStatus,
) {
    updateActiveSession(sessionId) { session ->
        session.copy(
            messages = session.messages.map { message ->
                val interaction = message.interaction ?: return@map message
                if (interaction.toolCalls.none { toolCall -> toolCall.id == toolCallId }) {
                    return@map message
                }
                message.copy(
                    interaction = interaction.copy(
                        toolCalls = interaction.toolCalls.map { toolCall ->
                            if (toolCall.id == toolCallId) {
                                toolCall.copy(status = status)
                            } else {
                                toolCall
                            }
                        },
                    ),
                )
            },
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}

internal fun ChatViewModel.createMessageInternal(
    role: MessageRole,
    content: String,
    kind: MessageKind,
    imagePath: String? = null,
    imagePaths: List<String> = emptyList(),
    toolName: String? = null,
    toolArgsJson: String? = null,
    toolCallId: String? = null,
    toolCallStatus: PersistedToolCallStatus = PersistedToolCallStatus.PENDING,
    requestId: String? = null,
    finishReason: String? = null,
    terminalEventSeen: Boolean = false,
): MessageUiModel {
    val assistantToolCall = if (
        role == MessageRole.ASSISTANT &&
        kind == MessageKind.TOOL &&
        !toolName.isNullOrBlank() &&
        !toolArgsJson.isNullOrBlank()
    ) {
        listOf(
            PersistedToolCall(
                id = toolCallId ?: newToolCallId(),
                name = toolName,
                argumentsJson = toolArgsJson,
                status = toolCallStatus,
            ),
        )
    } else {
        emptyList()
    }
    val legacyUserToolCall = if (
        role == MessageRole.USER &&
        kind == MessageKind.TOOL &&
        !toolName.isNullOrBlank() &&
        !toolArgsJson.isNullOrBlank()
    ) {
        listOf(
            PersistedToolCall(
                id = toolCallId ?: newToolCallId(),
                name = toolName,
                argumentsJson = toolArgsJson,
                status = toolCallStatus,
            ),
        )
    } else {
        emptyList()
    }
    val interactionToolCall = if (assistantToolCall.isNotEmpty()) assistantToolCall else legacyUserToolCall
    val resolvedToolCallId = when {
        role == MessageRole.TOOL -> toolCallId
        interactionToolCall.isNotEmpty() -> interactionToolCall.first().id
        else -> toolCallId
    }
    val interaction = PersistedInteractionMessage(
        role = role.name,
        parts = listOf(PersistedInteractionPart(type = "text", text = content)),
        toolCalls = interactionToolCall,
        toolCallId = resolvedToolCallId,
        metadata = buildMap {
            put("kind", kind.name)
            imagePath?.let { put("imagePath", it) }
            toolName?.let { put("toolName", it) }
            resolvedToolCallId?.let { put("toolCallId", it) }
        },
    )
    return MessageUiModel(
        id = newMessageId(prefix = "msg-${role.name.lowercase()}"),
        role = role,
        content = content,
        timestampEpochMs = System.currentTimeMillis(),
        kind = kind,
        imagePath = imagePath,
        imagePaths = imagePaths,
        toolName = toolName,
        isStreaming = false,
        requestId = requestId,
        finishReason = finishReason,
        terminalEventSeen = terminalEventSeen,
        interaction = interaction,
    )
}

internal fun ChatViewModel.maybeAdvanceAfterAssistantResponseInternal() {
    val snapshot = _uiState.value
    if (snapshot.activeSurface is com.pocketagent.android.ui.state.ModalSurface.Onboarding) {
        return
    }
    if (!snapshot.firstAnswerCompleted) {
        onFirstAnswerCompleted()
        return
    }
    if (!snapshot.followUpCompleted) {
        onFollowUpCompleted()
        if (!snapshot.advancedUnlocked) {
            onAdvancedUnlocked()
        }
    }
}

internal fun ChatViewModel.ensureSimpleFirstEnteredTelemetryIfNeededInternal() {
    val state = _uiState.value
    if (
        state.activeSurface is com.pocketagent.android.ui.state.ModalSurface.Onboarding &&
        state.firstSessionTelemetryEvents.any { it.eventName == TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED }
    ) {
        return
    }
    if (state.activeSurface !is com.pocketagent.android.ui.state.ModalSurface.Onboarding) {
        recordFirstSessionEventOnce(TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED)
    }
}

internal fun ChatViewModel.recordFirstSessionEventOnceInternal(eventName: String) {
    var changed = false
    _uiState.update { state ->
        val updatedEvents = addTelemetryEventIfMissing(
            events = state.firstSessionTelemetryEvents,
            eventName = eventName,
        )
        changed = updatedEvents.size != state.firstSessionTelemetryEvents.size
        state.copy(
            firstSessionTelemetryEvents = updatedEvents,
        )
    }
    if (changed) {
        persistState()
    }
}
