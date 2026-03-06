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
        assertEquals("USER", decoded.sessions.first().messages.first().interaction?.role)
        assertEquals("text", decoded.sessions.first().messages.first().interaction?.parts?.firstOrNull()?.type)
    }

    @Test
    fun `decode tolerates sparse json and uses safe defaults`() {
        val decoded = PersistedChatStateCodec.decode("""{"sessions":[{"id":"s1","messages":[{}]}]}""")

        assertEquals(1, decoded.sessions.size)
        assertNull(decoded.activeSessionId)
        assertEquals("AUTO", decoded.routingMode)
        assertFalse(decoded.onboardingCompleted)
    }

    @Test
    fun `decode preserves backward compatibility when terminal metadata fields are absent`() {
        val decoded = PersistedChatStateCodec.decode(
            """
            {
              "activeSessionId": "s1",
              "sessions": [
                {
                  "id": "s1",
                  "title": "Legacy session",
                  "messages": [
                    {
                      "id": "m1",
                      "role": "ASSISTANT",
                      "content": "legacy payload",
                      "timestampEpochMs": 1,
                      "kind": "TEXT",
                      "isStreaming": false
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val message = decoded.sessions.single().messages.single()
        assertNull(message.requestId)
        assertNull(message.finishReason)
        assertFalse(message.terminalEventSeen)
        assertEquals("ASSISTANT", message.interaction?.role)
        assertTrue(message.interaction?.metadata?.get("source")?.contains("legacy") == true)
    }

    @Test
    fun `decode preserves explicit interaction payload when present`() {
        val decoded = PersistedChatStateCodec.decode(
            """
            {
              "activeSessionId": "s1",
              "sessions": [
                {
                  "id": "s1",
                  "title": "Tool call session",
                  "messages": [
                    {
                      "id": "m1",
                      "role": "USER",
                      "content": "calculate 1+2",
                      "timestampEpochMs": 1,
                      "kind": "TOOL",
                      "interaction": {
                        "role": "USER",
                        "toolCallId": "tc-1",
                        "parts": [{"type":"text","text":"calculate 1+2"}],
                        "toolCalls": [{"id":"tc-1","name":"calculator","argumentsJson":"{\"expression\":\"1+2\"}"}],
                        "metadata": {"kind":"TOOL"}
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val interaction = decoded.sessions.single().messages.single().interaction
        assertEquals("USER", interaction?.role)
        assertEquals("tc-1", interaction?.toolCallId)
        assertEquals("calculator", interaction?.toolCalls?.singleOrNull()?.name)
    }
}
