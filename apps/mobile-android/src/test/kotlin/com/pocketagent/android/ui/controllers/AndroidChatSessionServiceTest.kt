package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.CompletionSettings
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidChatSessionServiceTest {
    private val service = AndroidChatSessionService()

    @Test
    fun `switch session preserves completion settings for all sessions`() {
        val firstSettings = CompletionSettings(maxTokens = 1024, temperature = 0.3f, showThinking = true)
        val secondSettings = CompletionSettings(maxTokens = 2048, topK = 120, showThinking = false)
        val sessions = listOf(
            session(
                id = "session-1",
                completionSettings = firstSettings,
                message = message(id = "m1", role = MessageRole.USER, content = "hello"),
            ),
            session(
                id = "session-2",
                completionSettings = secondSettings,
                message = message(id = "m2", role = MessageRole.USER, content = "hi"),
            ),
        )

        val result = service.switchSession(
            sessions = sessions,
            activeSessionId = "session-1",
            sessionId = "session-2",
        )

        val byId = result.sessions.associateBy { it.id }
        assertEquals(firstSettings, byId.getValue("session-1").completionSettings)
        assertEquals(secondSettings, byId.getValue("session-2").completionSettings)
        assertEquals("session-2", result.activeSessionId)
    }

    @Test
    fun `hydrate session preserves completion settings`() {
        val settings = CompletionSettings(maxTokens = 3072, topP = 0.82f, repeatPenalty = 1.23f)
        val sessions = listOf(
            session(
                id = "session-1",
                completionSettings = settings,
                messages = emptyList(),
                messagesLoaded = false,
                messageCount = 2,
            ),
        )

        val hydratedMessages = listOf(
            message(id = "m1", role = MessageRole.USER, content = "question"),
            message(id = "m2", role = MessageRole.ASSISTANT, content = "answer"),
        )

        val result = service.hydrateSession(
            sessions = sessions,
            sessionId = "session-1",
            messages = hydratedMessages,
        )

        assertEquals(settings, result.sessions.single().completionSettings)
        assertEquals(true, result.sessions.single().messagesLoaded)
    }

    @Test
    fun `normalize preserves completion settings`() {
        val settings = CompletionSettings(maxTokens = 1536, frequencyPenalty = 0.7f, presencePenalty = 0.4f)
        val normalized = service.normalize(
            session(
                id = "session-1",
                completionSettings = settings,
                message = message(id = "m1", role = MessageRole.USER, content = "hello"),
            ),
        )

        assertEquals(settings, normalized.completionSettings)
    }
}

private fun session(
    id: String,
    completionSettings: CompletionSettings,
    message: MessageUiModel? = null,
    messages: List<MessageUiModel> = listOfNotNull(message),
    messagesLoaded: Boolean = true,
    messageCount: Int = messages.size,
): ChatSessionUiModel {
    val now = 1_700_000_000_000L
    return ChatSessionUiModel(
        id = id,
        title = "Session $id",
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        messages = messages,
        completionSettings = completionSettings,
        messagesLoaded = messagesLoaded,
        messageCount = messageCount,
    )
}

private fun message(
    id: String,
    role: MessageRole,
    content: String,
): MessageUiModel {
    return MessageUiModel(
        id = id,
        role = role,
        content = content,
        timestampEpochMs = 1_700_000_000_000L,
    )
}
