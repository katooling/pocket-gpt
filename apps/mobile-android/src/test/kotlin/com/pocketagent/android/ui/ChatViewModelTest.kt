package com.pocketagent.android.ui

import com.pocketagent.android.ChatResponse
import com.pocketagent.android.RoutingMode
import com.pocketagent.android.ui.runtime.ChatStreamEvent
import com.pocketagent.android.ui.runtime.MvpRuntimeFacade
import com.pocketagent.android.ui.runtime.StreamUserMessageRequest
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.SessionPersistence
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `send message streams tokens and persists assistant output`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            ioDispatcher = dispatcher,
        )

        viewModel.onComposerChanged("hello ui")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val activeSession = state.activeSession!!
        assertTrue(activeSession.messages.any { it.role == MessageRole.USER && it.content == "hello ui" })
        assertTrue(activeSession.messages.any { it.role == MessageRole.ASSISTANT && it.content.contains("response for hello ui") })
        assertEquals("auto", state.runtime.activeModelId)
        assertTrue(persistence.savedStates.isNotEmpty())
    }

    @Test
    fun `bootstraps from persisted sessions and restores turns`() = runTest(dispatcher) {
        val persistedSession = ChatSessionUiModel(
            id = "persisted-1",
            title = "Persisted",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            messages = listOf(
                MessageUiModel(
                    id = "m1",
                    role = MessageRole.USER,
                    content = "remember launch checklist",
                    timestampEpochMs = 10L,
                    kind = MessageKind.TEXT,
                ),
                MessageUiModel(
                    id = "m2",
                    role = MessageRole.ASSISTANT,
                    content = "noted",
                    timestampEpochMs = 11L,
                    kind = MessageKind.TEXT,
                ),
            ),
        )
        val persistence = RecordingPersistence(
            initialState = PersistedChatState(
                sessions = listOf(persistedSession),
                activeSessionId = persistedSession.id,
                routingMode = RoutingMode.QWEN_0_8B.name,
            ),
        )
        val runtime = RecordingRuntimeFacade()

        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertEquals(RoutingMode.QWEN_0_8B, viewModel.uiState.value.runtime.routingMode)
        assertEquals(1, runtime.restoredTurns.size)
        assertEquals("persisted-1", runtime.restoredTurns.first().first.value)
        assertEquals(2, runtime.restoredTurns.first().second.size)
    }
}

private class RecordingPersistence(
    private val initialState: PersistedChatState = PersistedChatState(),
) : SessionPersistence {
    val savedStates = mutableListOf<PersistedChatState>()
    private var current = initialState

    override fun loadState(): PersistedChatState = current

    override fun saveState(state: PersistedChatState) {
        current = state
        savedStates += state
    }
}

private class RecordingRuntimeFacade : MvpRuntimeFacade {
    private var sessionCounter = 0
    private var routingMode: RoutingMode = RoutingMode.AUTO
    val restoredTurns = mutableListOf<Pair<SessionId, List<Turn>>>()

    override fun createSession(): SessionId {
        sessionCounter += 1
        return SessionId("session-$sessionCounter")
    }

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.Token(token = "stream ", accumulatedText = "stream"))
        emit(ChatStreamEvent.Token(token = "token ", accumulatedText = "stream token"))
        emit(
            ChatStreamEvent.Completed(
                response = ChatResponse(
                    sessionId = request.sessionId,
                    modelId = "auto",
                    text = "response for ${request.userText}",
                    firstTokenLatencyMs = 25,
                    totalLatencyMs = 75,
                ),
            ),
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String = "tool:$toolName"

    override fun analyzeImage(imagePath: String, prompt: String): String = "image:$imagePath"

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        restoredTurns += sessionId to turns
    }

    override fun deleteSession(sessionId: SessionId): Boolean = true
}
