package com.pocketagent.runtime

import com.pocketagent.core.ConversationModule
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.memory.MemoryChunk
import com.pocketagent.memory.MemoryModule

class RuntimeSessionManager(
    private val conversationModule: ConversationModule,
    private val memoryModule: MemoryModule,
) {
    fun createSession(): SessionId = conversationModule.createSession()

    fun listSessions(): List<SessionId> = conversationModule.listSessions()

    fun listTurns(sessionId: SessionId): List<Turn> = conversationModule.listTurns(sessionId)

    fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        conversationModule.restoreSession(sessionId, turns)
        turns.filter { it.role == "user" }.forEach { turn ->
            memoryModule.saveMemoryChunk(
                MemoryChunk(
                    id = "restore-${sessionId.value}-${turn.timestampEpochMs}",
                    content = turn.content,
                    createdAtEpochMs = turn.timestampEpochMs,
                ),
            )
        }
    }

    fun deleteSession(sessionId: SessionId): Boolean = conversationModule.deleteSession(sessionId)
}
