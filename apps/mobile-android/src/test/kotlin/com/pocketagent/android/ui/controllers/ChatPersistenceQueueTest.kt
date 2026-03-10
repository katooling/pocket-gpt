package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.PersistedChatState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPersistenceQueueTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `queue debounces writes and persists latest snapshot`() = runTest(dispatcher) {
        val persisted = mutableListOf<PersistedChatState>()
        val queue = ChatPersistenceQueue(
            scope = this,
            ioDispatcher = dispatcher,
            debounceMs = 100L,
            toPersistedState = { state ->
                PersistedChatState(activeSessionId = state.composer.text.ifBlank { null })
            },
            savePersistedState = { state ->
                persisted += state
            },
        )

        queue.enqueue(ChatUiState(composer = ComposerUiState(text = "first")))
        queue.enqueue(ChatUiState(composer = ComposerUiState(text = "latest")))
        advanceTimeBy(99L)
        assertTrue(persisted.isEmpty())

        advanceTimeBy(1L)
        advanceUntilIdle()

        assertEquals(1, persisted.size)
        assertEquals("latest", persisted.single().activeSessionId)
    }

    @Test
    fun `queue metrics track write count and medians`() = runTest(dispatcher) {
        val queue = ChatPersistenceQueue(
            scope = this,
            ioDispatcher = dispatcher,
            debounceMs = 10L,
            toPersistedState = { state ->
                PersistedChatState(activeSessionId = state.composer.text.ifBlank { null })
            },
            savePersistedState = {},
        )

        queue.enqueue(ChatUiState(composer = ComposerUiState(text = "one")))
        queue.enqueue(ChatUiState(composer = ComposerUiState(text = "two")))
        advanceUntilIdle()

        val metrics = queue.metricsSnapshot()
        assertTrue(metrics.writeCount > 0)
        assertTrue(metrics.lastPayloadBytes > 0)
        assertTrue(metrics.medianPayloadBytes > 0)
    }
}

