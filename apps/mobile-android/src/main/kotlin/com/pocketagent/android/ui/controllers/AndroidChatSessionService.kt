package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.runtime.ChatSessionRecord
import com.pocketagent.runtime.ChatSessionService

class AndroidChatSessionService(
    private val service: ChatSessionService<MessageUiModel> = ChatSessionService(),
) {
    data class SessionBootstrapPlan(
        val sessions: List<ChatSessionUiModel>,
        val activeSessionId: String?,
        val hydrateSessionId: String?,
        val shouldCreateInitialSession: Boolean,
        val shouldPersist: Boolean,
    )

    data class SessionMutationResult(
        val sessions: List<ChatSessionUiModel>,
        val activeSessionId: String?,
        val hydrateSessionId: String?,
        val shouldPersist: Boolean,
        val shouldCreateReplacementSession: Boolean = false,
    )

    fun bootstrap(
        sessions: List<ChatSessionUiModel>,
        persistedActiveSessionId: String?,
    ): SessionBootstrapPlan {
        val completionSettingsBySessionId = sessions.associate { it.id to it.completionSettings }
        val plan = service.bootstrap(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            persistedActiveSessionId = persistedActiveSessionId,
        )
        return SessionBootstrapPlan(
            sessions = plan.sessions.map { record ->
                record.toUiModel(
                    completionSettings = completionSettingsBySessionId[record.id] ?: CompletionSettings(),
                )
            },
            activeSessionId = plan.activeSessionId,
            hydrateSessionId = plan.hydrateSessionId,
            shouldCreateInitialSession = plan.shouldCreateInitialSession,
            shouldPersist = plan.shouldPersist,
        )
    }

    fun createSession(
        sessions: List<ChatSessionUiModel>,
        sessionId: String,
        title: String,
        nowEpochMs: Long,
    ): SessionMutationResult {
        val completionSettingsBySessionId = sessions.associate { it.id to it.completionSettings }
        val mutation = service.createSession(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            sessionId = sessionId,
            title = title,
            nowEpochMs = nowEpochMs,
        )
        return SessionMutationResult(
            sessions = mutation.sessions.map { record ->
                record.toUiModel(
                    completionSettings = completionSettingsBySessionId[record.id] ?: CompletionSettings(),
                )
            },
            activeSessionId = mutation.activeSessionId,
            hydrateSessionId = mutation.hydrateSessionId,
            shouldPersist = mutation.shouldPersist,
            shouldCreateReplacementSession = mutation.shouldCreateReplacementSession,
        )
    }

    fun switchSession(
        sessions: List<ChatSessionUiModel>,
        activeSessionId: String?,
        sessionId: String,
    ): SessionMutationResult {
        val completionSettingsBySessionId = sessions.associate { it.id to it.completionSettings }
        val mutation = service.switchSession(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            activeSessionId = activeSessionId,
            sessionId = sessionId,
        )
        return SessionMutationResult(
            sessions = mutation.sessions.map { record ->
                record.toUiModel(
                    completionSettings = completionSettingsBySessionId[record.id] ?: CompletionSettings(),
                )
            },
            activeSessionId = mutation.activeSessionId,
            hydrateSessionId = mutation.hydrateSessionId,
            shouldPersist = mutation.shouldPersist,
            shouldCreateReplacementSession = mutation.shouldCreateReplacementSession,
        )
    }

    fun deleteSession(
        sessions: List<ChatSessionUiModel>,
        activeSessionId: String?,
        sessionId: String,
    ): SessionMutationResult {
        val completionSettingsBySessionId = sessions.associate { it.id to it.completionSettings }
        val mutation = service.deleteSession(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            activeSessionId = activeSessionId,
            sessionId = sessionId,
        )
        return SessionMutationResult(
            sessions = mutation.sessions.map { record ->
                record.toUiModel(
                    completionSettings = completionSettingsBySessionId[record.id] ?: CompletionSettings(),
                )
            },
            activeSessionId = mutation.activeSessionId,
            hydrateSessionId = mutation.hydrateSessionId,
            shouldPersist = mutation.shouldPersist,
            shouldCreateReplacementSession = mutation.shouldCreateReplacementSession,
        )
    }

    fun hydrateSession(
        sessions: List<ChatSessionUiModel>,
        sessionId: String,
        messages: List<MessageUiModel>,
    ): SessionMutationResult {
        val completionSettingsBySessionId = sessions.associate { it.id to it.completionSettings }
        val mutation = service.hydrateSession(
            sessions = sessions.map(ChatSessionUiModel::toRecord),
            sessionId = sessionId,
            messages = messages,
        )
        return SessionMutationResult(
            sessions = mutation.sessions.map { record ->
                record.toUiModel(
                    completionSettings = completionSettingsBySessionId[record.id] ?: CompletionSettings(),
                )
            },
            activeSessionId = mutation.activeSessionId,
            hydrateSessionId = mutation.hydrateSessionId,
            shouldPersist = mutation.shouldPersist,
            shouldCreateReplacementSession = mutation.shouldCreateReplacementSession,
        )
    }

    fun normalize(session: ChatSessionUiModel): ChatSessionUiModel {
        return service.normalize(session.toRecord()).toUiModel(completionSettings = session.completionSettings)
    }
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

private fun ChatSessionRecord<MessageUiModel>.toUiModel(
    completionSettings: CompletionSettings,
): ChatSessionUiModel {
    return ChatSessionUiModel(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        messages = messages,
        completionSettings = completionSettings,
        messagesLoaded = messagesLoaded,
        messageCount = messageCount,
    )
}
