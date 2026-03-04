package com.pocketagent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryConversationModuleTest {
    @Test
    fun `restore and list session history for restart continuity`() {
        val module = InMemoryConversationModule()
        val session = SessionId("session-42")
        val turns = listOf(
            Turn(role = "user", content = "hello", timestampEpochMs = 1),
            Turn(role = "assistant", content = "hi there", timestampEpochMs = 2),
        )

        module.restoreSession(session, turns)

        assertTrue(module.listSessions().contains(session))
        assertEquals(turns, module.listTurns(session))
        assertTrue(module.buildPromptContext(session).contains("assistant: hi there"))
    }

    @Test
    fun `delete session removes persisted turns`() {
        val module = InMemoryConversationModule()
        val session = module.createSession()
        module.appendUserTurn(session, "hello")
        module.appendAssistantTurn(session, "hi")

        assertTrue(module.deleteSession(session))
        assertEquals(emptyList(), module.listTurns(session))
        assertFalse(module.listSessions().contains(session))
    }
}
