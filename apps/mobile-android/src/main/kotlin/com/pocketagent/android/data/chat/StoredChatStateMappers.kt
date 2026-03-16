package com.pocketagent.android.data.chat

import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.MessageUiModel

internal fun ChatUiState.toStoredChatState(): StoredChatState {
    return StoredChatState(
        sessions = sessions.map { session -> session.toStoredSession(clearStreaming = true) },
        activeSessionId = activeSessionId,
        routingMode = runtime.routingMode.name,
        performanceProfile = runtime.performanceProfile.name,
        keepAlivePreference = runtime.keepAlivePreference.name,
        gpuAccelerationEnabled = runtime.gpuAccelerationEnabled,
        defaultThinkingEnabled = defaultThinkingEnabled,
        onboardingCompleted = !showOnboarding,
        firstSessionStage = firstSessionStage.name,
        advancedUnlocked = advancedUnlocked,
        firstAnswerCompleted = firstAnswerCompleted,
        followUpCompleted = followUpCompleted,
        firstSessionTelemetryEvents = firstSessionTelemetryEvents,
    )
}

internal fun StoredChatSession.toUiSession(): ChatSessionUiModel {
    return ChatSessionUiModel(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        messages = messages.map(StoredChatMessage::toUiMessage),
        completionSettings = completionSettings,
        messagesLoaded = messagesLoaded,
        messageCount = messageCount,
    )
}

internal fun StoredChatMessage.toUiMessage(): MessageUiModel {
    return MessageUiModel(
        id = id,
        role = role,
        content = content,
        timestampEpochMs = timestampEpochMs,
        kind = kind,
        imagePath = imagePath,
        imagePaths = imagePaths,
        toolName = toolName,
        isStreaming = isStreaming,
        requestId = requestId,
        finishReason = finishReason,
        terminalEventSeen = terminalEventSeen,
        isThinking = isThinking,
        interaction = interaction,
        reasoningContent = reasoningContent,
        firstTokenMs = firstTokenMs,
        tokensPerSec = tokensPerSec,
        totalLatencyMs = totalLatencyMs,
    )
}

internal fun ChatSessionUiModel.toStoredSession(
    clearStreaming: Boolean = false,
): StoredChatSession {
    return StoredChatSession(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        messages = messages.map { message -> message.toStoredMessage(clearStreaming = clearStreaming) },
        completionSettings = completionSettings,
        messagesLoaded = messagesLoaded,
        messageCount = if (messagesLoaded) messages.size else messageCount,
    )
}

internal fun MessageUiModel.toStoredMessage(
    clearStreaming: Boolean = false,
): StoredChatMessage {
    return StoredChatMessage(
        id = id,
        role = role,
        content = content,
        timestampEpochMs = timestampEpochMs,
        kind = kind,
        imagePath = imagePath,
        imagePaths = imagePaths,
        toolName = toolName,
        isStreaming = if (clearStreaming) false else isStreaming,
        requestId = requestId,
        finishReason = finishReason,
        terminalEventSeen = terminalEventSeen,
        isThinking = isThinking,
        interaction = interaction,
        reasoningContent = reasoningContent,
        firstTokenMs = firstTokenMs,
        tokensPerSec = tokensPerSec,
        totalLatencyMs = totalLatencyMs,
    )
}
