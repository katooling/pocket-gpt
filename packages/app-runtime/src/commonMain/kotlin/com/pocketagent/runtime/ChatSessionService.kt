package com.pocketagent.runtime

data class ChatSessionRecord<TMessage>(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val messages: List<TMessage>,
    val messagesLoaded: Boolean,
    val messageCount: Int,
)

data class ChatSessionBootstrapPlan<TMessage>(
    val sessions: List<ChatSessionRecord<TMessage>>,
    val activeSessionId: String?,
    val hydrateSessionId: String?,
    val shouldCreateInitialSession: Boolean,
    val shouldPersist: Boolean,
)

data class ChatSessionMutationResult<TMessage>(
    val sessions: List<ChatSessionRecord<TMessage>>,
    val activeSessionId: String?,
    val hydrateSessionId: String?,
    val shouldPersist: Boolean,
    val shouldCreateReplacementSession: Boolean = false,
)

class ChatSessionService<TMessage> {
    fun bootstrap(
        sessions: List<ChatSessionRecord<TMessage>>,
        persistedActiveSessionId: String?,
    ): ChatSessionBootstrapPlan<TMessage> {
        if (sessions.isEmpty()) {
            return ChatSessionBootstrapPlan(
                sessions = emptyList(),
                activeSessionId = null,
                hydrateSessionId = null,
                shouldCreateInitialSession = true,
                shouldPersist = false,
            )
        }
        val normalizedSessions = sessions.map(::normalize)
        val resolvedActiveSessionId = persistedActiveSessionId
            ?.takeIf { candidate -> normalizedSessions.any { it.id == candidate } }
            ?: normalizedSessions.last().id
        return ChatSessionBootstrapPlan(
            sessions = normalizedSessions,
            activeSessionId = resolvedActiveSessionId,
            hydrateSessionId = normalizedSessions
                .firstOrNull { it.id == resolvedActiveSessionId && !it.messagesLoaded }
                ?.id,
            shouldCreateInitialSession = false,
            shouldPersist = normalizedSessions != sessions || resolvedActiveSessionId != persistedActiveSessionId,
        )
    }

    fun createSession(
        sessions: List<ChatSessionRecord<TMessage>>,
        sessionId: String,
        title: String,
        nowEpochMs: Long,
    ): ChatSessionMutationResult<TMessage> {
        val normalizedSessions = sessions.map(::normalize)
        val newSession = ChatSessionRecord<TMessage>(
            id = sessionId,
            title = title,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            messages = emptyList<TMessage>(),
            messagesLoaded = true,
            messageCount = 0,
        )
        return ChatSessionMutationResult(
            sessions = normalizedSessions + newSession,
            activeSessionId = sessionId,
            hydrateSessionId = null,
            shouldPersist = true,
        )
    }

    fun switchSession(
        sessions: List<ChatSessionRecord<TMessage>>,
        activeSessionId: String?,
        sessionId: String,
    ): ChatSessionMutationResult<TMessage> {
        val normalizedSessions = sessions.map(::normalize)
        val target = normalizedSessions.firstOrNull { it.id == sessionId }
            ?: return ChatSessionMutationResult(
                sessions = normalizedSessions,
                activeSessionId = activeSessionId,
                hydrateSessionId = null,
                shouldPersist = false,
            )
        return ChatSessionMutationResult(
            sessions = normalizedSessions,
            activeSessionId = target.id,
            hydrateSessionId = target.id.takeIf { !target.messagesLoaded },
            shouldPersist = activeSessionId != target.id,
        )
    }

    fun deleteSession(
        sessions: List<ChatSessionRecord<TMessage>>,
        activeSessionId: String?,
        sessionId: String,
    ): ChatSessionMutationResult<TMessage> {
        val normalizedSessions = sessions.map(::normalize)
        val remaining = normalizedSessions.filterNot { it.id == sessionId }
        if (remaining.isEmpty()) {
            return ChatSessionMutationResult(
                sessions = emptyList(),
                activeSessionId = null,
                hydrateSessionId = null,
                shouldPersist = true,
                shouldCreateReplacementSession = true,
            )
        }
        val nextActiveSessionId = when {
            activeSessionId == null -> remaining.last().id
            activeSessionId == sessionId -> remaining.last().id
            remaining.any { it.id == activeSessionId } -> activeSessionId
            else -> remaining.last().id
        }
        return ChatSessionMutationResult(
            sessions = remaining,
            activeSessionId = nextActiveSessionId,
            hydrateSessionId = remaining
                .firstOrNull { it.id == nextActiveSessionId && !it.messagesLoaded }
                ?.id,
            shouldPersist = true,
        )
    }

    fun hydrateSession(
        sessions: List<ChatSessionRecord<TMessage>>,
        sessionId: String,
        messages: List<TMessage>,
    ): ChatSessionMutationResult<TMessage> {
        val normalizedSessions = sessions.map { session ->
            if (session.id != sessionId) {
                normalize(session)
            } else {
                normalize(
                    session.copy(
                        messages = messages,
                        messagesLoaded = true,
                        messageCount = messages.size,
                    ),
                )
            }
        }
        return ChatSessionMutationResult(
            sessions = normalizedSessions,
            activeSessionId = null,
            hydrateSessionId = null,
            shouldPersist = true,
        )
    }

    fun normalize(session: ChatSessionRecord<TMessage>): ChatSessionRecord<TMessage> {
        return if (session.messagesLoaded) {
            session.copy(messageCount = session.messages.size)
        } else {
            session
        }
    }
}
