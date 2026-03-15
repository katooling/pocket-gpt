package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatSessionServiceTest {
    private val service = ChatSessionService<String>()

    @Test
    fun `bootstrap creates initial session plan when no sessions exist`() {
        val plan = service.bootstrap(
            sessions = emptyList(),
            persistedActiveSessionId = null,
        )

        assertTrue(plan.shouldCreateInitialSession)
        assertNull(plan.activeSessionId)
        assertNull(plan.hydrateSessionId)
        assertFalse(plan.shouldPersist)
    }

    @Test
    fun `bootstrap falls back to last session and schedules hydration when active session is unloaded`() {
        val plan = service.bootstrap(
            sessions = listOf(
                session(id = "s1", messages = listOf("a"), messagesLoaded = true, messageCount = 99),
                session(id = "s2", messages = emptyList(), messagesLoaded = false, messageCount = 4),
            ),
            persistedActiveSessionId = "missing",
        )

        assertFalse(plan.shouldCreateInitialSession)
        assertEquals("s2", plan.activeSessionId)
        assertEquals("s2", plan.hydrateSessionId)
        assertEquals(1, plan.sessions.first().messageCount)
        assertTrue(plan.shouldPersist)
    }

    @Test
    fun `switch session only persists when active target changes`() {
        val unchanged = service.switchSession(
            sessions = listOf(session(id = "s1"), session(id = "s2")),
            activeSessionId = "s2",
            sessionId = "s2",
        )
        val changed = service.switchSession(
            sessions = listOf(session(id = "s1"), session(id = "s2", messagesLoaded = false)),
            activeSessionId = "s1",
            sessionId = "s2",
        )

        assertFalse(unchanged.shouldPersist)
        assertEquals("s2", changed.activeSessionId)
        assertEquals("s2", changed.hydrateSessionId)
        assertTrue(changed.shouldPersist)
    }

    @Test
    fun `delete last session requests replacement creation`() {
        val mutation = service.deleteSession(
            sessions = listOf(session(id = "s1")),
            activeSessionId = "s1",
            sessionId = "s1",
        )

        assertTrue(mutation.shouldCreateReplacementSession)
        assertTrue(mutation.sessions.isEmpty())
        assertNull(mutation.activeSessionId)
    }

    @Test
    fun `hydrate session replaces messages and normalizes count`() {
        val mutation = service.hydrateSession(
            sessions = listOf(session(id = "s1", messagesLoaded = false, messageCount = 12)),
            sessionId = "s1",
            messages = listOf("m1", "m2"),
        )

        val hydrated = mutation.sessions.single()
        assertTrue(hydrated.messagesLoaded)
        assertEquals(listOf("m1", "m2"), hydrated.messages)
        assertEquals(2, hydrated.messageCount)
        assertTrue(mutation.shouldPersist)
    }

    private fun session(
        id: String,
        messages: List<String> = emptyList(),
        messagesLoaded: Boolean = true,
        messageCount: Int = messages.size,
    ): ChatSessionRecord<String> {
        return ChatSessionRecord(
            id = id,
            title = id,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            messages = messages,
            messagesLoaded = messagesLoaded,
            messageCount = messageCount,
        )
    }
}
