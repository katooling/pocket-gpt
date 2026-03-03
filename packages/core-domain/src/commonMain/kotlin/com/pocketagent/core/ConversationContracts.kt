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
    fun buildPromptContext(sessionId: SessionId): String
}
