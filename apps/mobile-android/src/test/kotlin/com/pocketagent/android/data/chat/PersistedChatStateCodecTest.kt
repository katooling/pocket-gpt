package com.pocketagent.android.data.chat

import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.FirstSessionTelemetryEvent
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.PersistedInteractionMessage
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedChatStateCodecTest {
    @Test
    fun `encode and decode roundtrip preserves sessions and onboarding flag`() {
        val state = StoredChatState(
            sessions = listOf(
                StoredChatSession(
                    id = "session-1",
                    title = "Launch checklist",
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 120L,
                    messages = listOf(
                        StoredChatMessage(
                            id = "msg-1",
                            role = MessageRole.USER,
                            content = "hello",
                            timestampEpochMs = 110L,
                            kind = MessageKind.TEXT,
                        ),
                        StoredChatMessage(
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
            keepAlivePreference = RuntimeKeepAlivePreference.FIVE_MINUTES.name,
            onboardingCompleted = true,
            firstSessionStage = FirstSessionStage.ADVANCED_UNLOCKED.name,
            advancedUnlocked = true,
            firstAnswerCompleted = true,
            followUpCompleted = true,
            firstSessionTelemetryEvents = listOf(
                FirstSessionTelemetryEvent(
                    eventName = "simple_first_entered",
                    eventTimeUtc = "2026-03-06T00:00:00Z",
                ),
            ),
        )

        val encoded = PersistedChatStateCodec.encode(state)
        val decoded = PersistedChatStateCodec.decode(encoded)

        assertEquals("session-1", decoded.activeSessionId)
        assertEquals("QWEN_0_8B", decoded.routingMode)
        assertEquals(RuntimeKeepAlivePreference.FIVE_MINUTES.name, decoded.keepAlivePreference)
        assertTrue(decoded.onboardingCompleted)
        assertEquals(FirstSessionStage.ADVANCED_UNLOCKED.name, decoded.firstSessionStage)
        assertTrue(decoded.advancedUnlocked)
        assertTrue(decoded.firstAnswerCompleted)
        assertTrue(decoded.followUpCompleted)
        assertEquals(1, decoded.firstSessionTelemetryEvents.size)
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
        assertEquals(RuntimeKeepAlivePreference.AUTO.name, decoded.keepAlivePreference)
        assertFalse(decoded.onboardingCompleted)
        assertEquals(FirstSessionStage.ONBOARDING.name, decoded.firstSessionStage)
        assertTrue(decoded.advancedUnlocked)
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
    fun `decode normalizes legacy imagePath into imagePaths`() {
        val decoded = PersistedChatStateCodec.decode(
            """
            {
              "activeSessionId": "s1",
              "sessions": [
                {
                  "id": "s1",
                  "title": "Legacy image",
                  "messages": [
                    {
                      "id": "m1",
                      "role": "USER",
                      "content": "see attachment",
                      "timestampEpochMs": 1,
                      "kind": "IMAGE",
                      "imagePath": "/tmp/legacy.png"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val message = decoded.sessions.single().messages.single()
        assertEquals("/tmp/legacy.png", message.imagePath)
        assertEquals(listOf("/tmp/legacy.png"), message.imagePaths)
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

    @Test
    fun `encode and decode preserve tool-role interaction linkage`() {
        val state = StoredChatState(
            sessions = listOf(
                StoredChatSession(
                    id = "s1",
                    title = "Tool role",
                    createdAtEpochMs = 1L,
                    updatedAtEpochMs = 2L,
                    messages = listOf(
                        StoredChatMessage(
                            id = "tool-1",
                            role = MessageRole.TOOL,
                            content = "{\"result\":3}",
                            timestampEpochMs = 3L,
                            kind = MessageKind.TOOL,
                            interaction = PersistedInteractionMessage(
                                role = MessageRole.TOOL.name,
                                parts = listOf(PersistedInteractionPart(type = "text", text = "{\"result\":3}")),
                                toolCallId = "tc-3",
                                metadata = mapOf("toolName" to "calculator"),
                            ),
                        ),
                    ),
                ),
            ),
            activeSessionId = "s1",
        )

        val encoded = PersistedChatStateCodec.encode(state)
        val decoded = PersistedChatStateCodec.decode(encoded)
        val message = decoded.sessions.single().messages.single()

        assertEquals(MessageRole.TOOL, message.role)
        assertEquals("TOOL", message.interaction?.role)
        assertEquals("tc-3", message.interaction?.toolCallId)
    }

    @Test
    fun `decode fails fast for invalid enum values`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatStateCodec.decode(
                """
                {
                  "sessions": [
                    {
                      "id": "s1",
                      "messages": [
                        {
                          "id": "m1",
                          "role": "NOT_A_ROLE",
                          "kind": "TEXT"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `decode fails fast for invalid typed fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatStateCodec.decode(
                """
                {
                  "onboardingCompleted": "sometimes",
                  "sessions": []
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `decode fails fast for invalid routing mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatStateCodec.decode(
                """
                {
                  "routingMode": "NOT_A_MODE",
                  "sessions": []
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `decode fails fast for invalid performance profile`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatStateCodec.decode(
                """
                {
                  "performanceProfile": "HYPERDRIVE",
                  "sessions": []
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `decode fails fast for invalid first session stage`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatStateCodec.decode(
                """
                {
                  "firstSessionStage": "INVALID_STAGE",
                  "sessions": []
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `decode fails fast for invalid keep alive preference`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedChatStateCodec.decode(
                """
                {
                  "keepAlivePreference": "FOREVER_PLUS",
                  "sessions": []
                }
                """.trimIndent(),
            )
        }
    }
}
