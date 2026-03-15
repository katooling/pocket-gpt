package com.pocketagent.android.ui.controllers

import com.pocketagent.android.data.chat.SessionPersistence
import com.pocketagent.android.data.chat.SessionStateLoadResult
import com.pocketagent.android.data.chat.StoredChatMessage
import com.pocketagent.android.data.chat.StoredChatState

class ChatPersistenceCoordinator(
    private val sessionPersistence: SessionPersistence,
) {
    fun loadState(): StoredChatState = sessionPersistence.loadState()

    fun loadBootstrapState(): StoredChatState = sessionPersistence.loadBootstrapState()

    fun loadStateResult(): SessionStateLoadResult = sessionPersistence.loadStateResult()

    fun loadBootstrapStateResult(): SessionStateLoadResult = sessionPersistence.loadBootstrapStateResult()

    fun loadSessionMessages(sessionId: String): List<StoredChatMessage>? = sessionPersistence.loadSessionMessages(sessionId)

    fun saveState(state: StoredChatState) {
        sessionPersistence.saveState(state)
    }

    fun clearState() {
        sessionPersistence.clearState()
    }
}
