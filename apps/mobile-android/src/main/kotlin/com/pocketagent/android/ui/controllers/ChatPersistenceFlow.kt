package com.pocketagent.android.ui.controllers

import com.pocketagent.android.data.chat.SessionStateLoadResult
import com.pocketagent.android.data.chat.StoredChatMessage
import com.pocketagent.android.data.chat.StoredChatState
import com.pocketagent.android.data.chat.toStoredChatState
import com.pocketagent.android.data.chat.toUiMessage
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.UiError

data class PersistenceBootstrapState(
    val persisted: StoredChatState,
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
            is SessionStateLoadResult.FatalCorruption -> StoredChatState()
        }
        val loadError = sessionStateLoadError(persistedResult)
        return PersistenceBootstrapState(
            persisted = persisted,
            loadError = loadError,
            shouldRunStartupProbe = loadError == null,
        )
    }

    fun toStoredState(state: ChatUiState): StoredChatState {
        return state.toStoredChatState()
    }

    fun saveStoredState(state: StoredChatState) {
        persistenceCoordinator.saveState(state)
    }

    fun saveState(state: ChatUiState) {
        saveStoredState(toStoredState(state))
    }

    fun loadSessionMessages(sessionId: String): List<MessageUiModel>? {
        return persistenceCoordinator.loadSessionMessages(sessionId)?.map(StoredChatMessage::toUiMessage)
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
