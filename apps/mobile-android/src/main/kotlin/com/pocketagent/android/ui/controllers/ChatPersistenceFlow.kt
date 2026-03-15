package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.SessionStateLoadResult
import com.pocketagent.android.ui.state.UiError

data class PersistenceBootstrapState(
    val persisted: PersistedChatState,
    val loadError: UiError?,
    val shouldRunStartupProbe: Boolean,
)

class ChatPersistenceFlow(
    private val persistenceCoordinator: ChatPersistenceCoordinator,
) {
    fun loadBootstrapState(): PersistenceBootstrapState {
        val persistedResult = persistenceCoordinator.loadBootstrapStateResult()
        val persisted = when (persistedResult) {
            is SessionStateLoadResult.Success -> persistedResult.state
            is SessionStateLoadResult.RecoverableCorruption -> persistedResult.resetState
            is SessionStateLoadResult.FatalCorruption -> PersistedChatState()
        }
        val loadError = sessionStateLoadError(persistedResult)
        return PersistenceBootstrapState(
            persisted = persisted,
            loadError = loadError,
            shouldRunStartupProbe = loadError == null,
        )
    }

    fun toPersistedState(state: ChatUiState): PersistedChatState {
        return PersistedChatState(
            sessions = state.sessions.map { session ->
                val persistedMessages = session.messages.map { message -> message.copy(isStreaming = false) }
                session.copy(
                    messages = persistedMessages,
                    messageCount = if (session.messagesLoaded) persistedMessages.size else session.messageCount,
                )
            },
            activeSessionId = state.activeSessionId,
            routingMode = state.runtime.routingMode.name,
            performanceProfile = state.runtime.performanceProfile.name,
            keepAlivePreference = state.runtime.keepAlivePreference.name,
            gpuAccelerationEnabled = state.runtime.gpuAccelerationEnabled,
            onboardingCompleted = !state.showOnboarding,
            firstSessionStage = state.firstSessionStage.name,
            advancedUnlocked = state.advancedUnlocked,
            firstAnswerCompleted = state.firstAnswerCompleted,
            followUpCompleted = state.followUpCompleted,
            firstSessionTelemetryEvents = state.firstSessionTelemetryEvents,
        )
    }

    fun savePersistedState(state: PersistedChatState) {
        persistenceCoordinator.saveState(state)
    }

    fun saveState(state: ChatUiState) {
        savePersistedState(toPersistedState(state))
    }

    fun loadSessionMessages(sessionId: String): List<MessageUiModel>? {
        return persistenceCoordinator.loadSessionMessages(sessionId)
    }

    private fun sessionStateLoadError(loadResult: SessionStateLoadResult): UiError? {
        return when (loadResult) {
            is SessionStateLoadResult.Success -> null
            is SessionStateLoadResult.RecoverableCorruption -> UiError(
                code = UI_SESSION_STATE_CORRUPTION_CODE,
                userMessage = "Saved chat state was corrupted and reset. Refresh runtime checks to continue.",
                technicalDetail = loadResult.technicalDetail,
            )
            is SessionStateLoadResult.FatalCorruption -> UiError(
                code = UI_SESSION_STATE_FATAL_CODE,
                userMessage = "Saved chat state could not be loaded. Refresh runtime checks and retry.",
                technicalDetail = loadResult.technicalDetail,
            )
        }
    }

    private companion object {
        private const val UI_SESSION_STATE_CORRUPTION_CODE = "UI-SESSION-001"
        private const val UI_SESSION_STATE_FATAL_CODE = "UI-SESSION-002"
    }
}
