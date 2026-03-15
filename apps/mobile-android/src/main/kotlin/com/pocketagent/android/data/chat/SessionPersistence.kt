package com.pocketagent.android.data.chat

sealed interface SessionStateLoadResult {
    data class Success(val state: StoredChatState) : SessionStateLoadResult

    data class RecoverableCorruption(
        val resetState: StoredChatState,
        val code: String,
        val userMessage: String,
        val technicalDetail: String,
    ) : SessionStateLoadResult

    data class FatalCorruption(
        val code: String,
        val userMessage: String,
        val technicalDetail: String,
    ) : SessionStateLoadResult
}

interface SessionPersistence {
    fun loadState(): StoredChatState

    fun loadBootstrapState(): StoredChatState = loadState()

    fun loadStateResult(): SessionStateLoadResult = SessionStateLoadResult.Success(loadState())

    fun loadBootstrapStateResult(): SessionStateLoadResult = SessionStateLoadResult.Success(loadBootstrapState())

    fun loadSessionMessages(sessionId: String): List<StoredChatMessage>? {
        return loadState().sessions.firstOrNull { it.id == sessionId }?.messages
    }

    fun saveState(state: StoredChatState)

    fun clearState() {}
}
