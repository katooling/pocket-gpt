package com.pocketagent.android.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPersistenceCodecTest {
    @Test
    fun `encode and decode roundtrip preserves sessions and onboarding flag`() {
        val state = PersistedChatState(
            sessions = listOf(
                ChatSessionUiModel(
                    id = "session-1",
                    title = "Launch checklist",
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 120L,
                    messages = listOf(
                        MessageUiModel(
                            id = "msg-1",
                            role = MessageRole.USER,
                            content = "hello",
                            timestampEpochMs = 110L,
                            kind = MessageKind.TEXT,
                        ),
                        MessageUiModel(
                            id = "msg-2",
                            role = MessageRole.ASSISTANT,
                            content = "world",
                            timestampEpochMs = 115L,
                            kind = MessageKind.TOOL,
                            toolName = "calculator",
                        ),
                    ),
                ),
            ),
            activeSessionId = "session-1",
            routingMode = "QWEN_0_8B",
            onboardingCompleted = true,
        )

        val encoded = PersistedChatStateCodec.encode(state)
        val decoded = PersistedChatStateCodec.decode(encoded)

        assertEquals("session-1", decoded.activeSessionId)
        assertEquals("QWEN_0_8B", decoded.routingMode)
        assertTrue(decoded.onboardingCompleted)
        assertEquals(1, decoded.sessions.size)
        assertEquals(2, decoded.sessions.first().messages.size)
        assertEquals("calculator", decoded.sessions.first().messages.last().toolName)
    }

    @Test
    fun `decode tolerates sparse json and uses safe defaults`() {
        val decoded = PersistedChatStateCodec.decode("""{"sessions":[{"id":"s1","messages":[{}]}]}""")

        assertEquals(1, decoded.sessions.size)
        assertNull(decoded.activeSessionId)
        assertEquals("AUTO", decoded.routingMode)
        assertFalse(decoded.onboardingCompleted)
    }
}
