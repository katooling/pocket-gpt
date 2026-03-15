package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.runtime.ChatSessionBootstrapPlan
import com.pocketagent.runtime.ChatSessionMutationResult
import com.pocketagent.runtime.ChatSessionRecord
import com.pocketagent.runtime.ChatSessionService

class AndroidChatSessionService(
    private val service: ChatSessionService<MessageUiModel> = ChatSessionService(),
) {
    fun bootstrap(
        sessions: List<ChatSessionUiModel>,
        persistedActiveSessionId: String?,
    ): ChatSessionBootstrapPlan<MessageUiModel> {
        return service.bootstrap(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            persistedActiveSessionId = persistedActiveSessionId,
        )
    }

    fun createSession(
        sessions: List<ChatSessionUiModel>,
        sessionId: String,
        title: String,
        nowEpochMs: Long,
    ): ChatSessionMutationResult<MessageUiModel> {
        return service.createSession(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            sessionId = sessionId,
            title = title,
            nowEpochMs = nowEpochMs,
        )
    }

    fun switchSession(
        sessions: List<ChatSessionUiModel>,
        activeSessionId: String?,
        sessionId: String,
    ): ChatSessionMutationResult<MessageUiModel> {
        return service.switchSession(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            activeSessionId = activeSessionId,
            sessionId = sessionId,
        )
    }

    fun deleteSession(
        sessions: List<ChatSessionUiModel>,
        activeSessionId: String?,
        sessionId: String,
    ): ChatSessionMutationResult<MessageUiModel> {
        return service.deleteSession(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            activeSessionId = activeSessionId,
            sessionId = sessionId,
        )
    }

    fun hydrateSession(
        sessions: List<ChatSessionUiModel>,
        sessionId: String,
        messages: List<MessageUiModel>,
    ): ChatSessionMutationResult<MessageUiModel> {
        return service.hydrateSession(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            sessionId = sessionId,
            messages = messages,
        )
    }

    fun normalize(session: ChatSessionUiModel): ChatSessionUiModel {
        return service.normalize(session.toRecord()).toUiModel()
    }
}

internal fun ChatSessionBootstrapPlan<MessageUiModel>.toUiSessions(): List<ChatSessionUiModel> {
    return sessions.map(ChatSessionRecord<MessageUiModel>::toUiModel)
}

internal fun ChatSessionMutationResult<MessageUiModel>.toUiSessions(): List<ChatSessionUiModel> {
    return sessions.map(ChatSessionRecord<MessageUiModel>::toUiModel)
}

private fun ChatSessionUiModel.toRecord(): ChatSessionRecord<MessageUiModel> {
    return ChatSessionRecord(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        messages = messages,
        messagesLoaded = messagesLoaded,
        messageCount = messageCount,
    )
}

private fun ChatSessionRecord<MessageUiModel>.toUiModel(): ChatSessionUiModel {
    return ChatSessionUiModel(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        messages = messages,
        messagesLoaded = messagesLoaded,
        messageCount = messageCount,
    )
}
