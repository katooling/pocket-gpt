package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.SessionStateLoadResult
import com.pocketagent.android.ui.state.SessionPersistence

class ChatPersistenceCoordinator(
    private val sessionPersistence: SessionPersistence,
) {
    fun loadState(): PersistedChatState = sessionPersistence.loadState()
    fun loadStateResult(): SessionStateLoadResult = sessionPersistence.loadStateResult()

    fun saveState(state: PersistedChatState) {
        sessionPersistence.saveState(state)
    }

    fun clearState() {
        sessionPersistence.clearState()
    }
}
