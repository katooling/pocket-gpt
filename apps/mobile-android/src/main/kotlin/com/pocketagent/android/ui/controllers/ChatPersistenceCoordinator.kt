package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.SessionStateLoadResult
import com.pocketagent.android.ui.state.SessionPersistence

class ChatPersistenceCoordinator(
    private val sessionPersistence: SessionPersistence,
) {
    fun loadState(): PersistedChatState = sessionPersistence.loadState()
    fun loadBootstrapState(): PersistedChatState = sessionPersistence.loadBootstrapState()
    fun loadStateResult(): SessionStateLoadResult = sessionPersistence.loadStateResult()
    fun loadBootstrapStateResult(): SessionStateLoadResult = sessionPersistence.loadBootstrapStateResult()
    fun loadSessionMessages(sessionId: String): List<MessageUiModel>? = sessionPersistence.loadSessionMessages(sessionId)

    fun saveState(state: PersistedChatState) {
        sessionPersistence.saveState(state)
    }

    fun clearState() {
        sessionPersistence.clearState()
    }
}
