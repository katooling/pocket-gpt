package com.pocketagent.core

class InMemoryConversationModule : ConversationModule {
    private val sessions: MutableMap<SessionId, MutableList<Turn>> = mutableMapOf()
    private var sessionCounter: Long = 0L

    override fun createSession(): SessionId {
        val session = nextSessionId()
        sessions[session] = mutableListOf()
        return session
    }

    override fun appendUserTurn(sessionId: SessionId, content: String): Turn {
        return appendTurn(sessionId, "user", content)
    }

    override fun appendAssistantTurn(sessionId: SessionId, content: String): Turn {
        return appendTurn(sessionId, "assistant", content)
    }

    @Deprecated("Template rendering now consumes structured turns; avoid using string prompt context directly.")
    override fun buildPromptContext(sessionId: SessionId): String {
        val turns = sessions[sessionId].orEmpty()
        return turns.takeLast(8).joinToString(separator = "\n") { turn ->
            "${turn.role}: ${turn.content}"
        }
    }

    override fun listSessions(): List<SessionId> = sessions.keys.toList()

    override fun listTurns(sessionId: SessionId): List<Turn> = sessions[sessionId].orEmpty()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        sessions[sessionId] = turns.toMutableList()
        val parsedId = sessionOrdinal(sessionId)
        if (parsedId != null && parsedId > sessionCounter) {
            sessionCounter = parsedId
        }
    }

    override fun deleteSession(sessionId: SessionId): Boolean {
        return sessions.remove(sessionId) != null
    }

    private fun appendTurn(sessionId: SessionId, role: String, content: String): Turn {
        val turns = sessions.getOrPut(sessionId) { mutableListOf() }
        val turn = Turn(
            role = role,
            content = content,
            timestampEpochMs = System.currentTimeMillis(),
        )
        turns.add(turn)
        return turn
    }

    private fun nextSessionId(): SessionId {
        while (true) {
            sessionCounter += 1
            val candidate = SessionId("session-$sessionCounter")
            if (!sessions.containsKey(candidate)) {
                return candidate
            }
        }
    }

    private fun sessionOrdinal(sessionId: SessionId): Long? {
        val match = SESSION_ID_REGEX.matchEntire(sessionId.value) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private companion object {
        val SESSION_ID_REGEX = Regex("^session-(\\d+)$")
    }
}
