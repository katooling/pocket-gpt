package com.pocketagent.core

data class SessionId(val value: String)

data class Turn(
    val role: String,
    val content: String,
    val timestampEpochMs: Long,
)

interface ConversationModule {
    fun createSession(): SessionId
    fun appendUserTurn(sessionId: SessionId, content: String): Turn
    fun appendAssistantTurn(sessionId: SessionId, content: String): Turn
    @Deprecated("Template rendering now consumes structured turns; avoid using string prompt context directly.")
    fun buildPromptContext(sessionId: SessionId): String
    fun listSessions(): List<SessionId>
    fun listTurns(sessionId: SessionId): List<Turn>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
}
