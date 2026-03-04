package com.pocketagent.core

class InMemoryConversationModule : ConversationModule {
    private val sessions: MutableMap<SessionId, MutableList<Turn>> = mutableMapOf()

    override fun createSession(): SessionId {
        val session = SessionId("session-${sessions.size + 1}")
        sessions[session] = mutableListOf()
        return session
    }

    override fun appendUserTurn(sessionId: SessionId, content: String): Turn {
        return appendTurn(sessionId, "user", content)
    }

    override fun appendAssistantTurn(sessionId: SessionId, content: String): Turn {
        return appendTurn(sessionId, "assistant", content)
    }

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
}
