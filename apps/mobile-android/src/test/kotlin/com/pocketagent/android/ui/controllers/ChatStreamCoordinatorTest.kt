package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeGateway
import com.pocketagent.android.ui.state.StreamReducerState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.StreamUserMessageRequest
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatStreamCoordinatorTest {
    @Test
    fun `collect stream emits terminal completed state`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(ChatStreamEvent.TokenDelta(request.requestId, token = "hello ", accumulatedText = "hello"))
                    emit(
                        ChatStreamEvent.Completed(
                            requestId = request.requestId,
                            response = ChatResponse(
                                sessionId = request.sessionId,
                                modelId = "auto",
                                text = "hello",
                                firstTokenLatencyMs = 1L,
                                totalLatencyMs = 2L,
                            ),
                            finishReason = "completed",
                        ),
                    )
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 200L)
        var beforeTerminalCalls = 0
        var terminalState: StreamTerminalState? = null
        var tokenEvents = 0

        coordinator.collectStream(
            runtimeGateway = runtime,
            request = request("req-complete"),
            requestTimeoutMs = 200L,
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { event, _: StreamReducerState ->
                if (event is ChatStreamEvent.TokenDelta) {
                    tokenEvents += 1
                }
            },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { beforeTerminalCalls += 1 },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(1, tokenEvents)
        assertEquals(1, beforeTerminalCalls)
        assertNotNull(terminalState)
        assertEquals("completed", terminalState?.finishReason)
    }

    @Test
    fun `collect stream timeout cancels request and emits timeout terminal`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    awaitCancellation()
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeGateway = runtime,
            request = request("req-timeout"),
            requestTimeoutMs = 30L,
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertTrue(runtime.cancelByRequestCalls > 0)
        assertNotNull(terminalState)
        assertEquals("timeout", terminalState?.finishReason)
    }

    @Test
    fun `collect stream does not timeout after first token`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(request.requestId, startedAtEpochMs = 1L))
                    emit(ChatStreamEvent.TokenDelta(request.requestId, token = "hello ", accumulatedText = "hello"))
                    delay(80L)
                    emit(
                        ChatStreamEvent.Completed(
                            requestId = request.requestId,
                            response = ChatResponse(
                                sessionId = request.sessionId,
                                modelId = "auto",
                                text = "hello world",
                                firstTokenLatencyMs = 1L,
                                totalLatencyMs = 90L,
                            ),
                            finishReason = "completed",
                        ),
                    )
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 30L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeGateway = runtime,
            request = request("req-post-token"),
            requestTimeoutMs = 30L,
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertEquals(0, runtime.cancelByRequestCalls)
        assertNotNull(terminalState)
        assertEquals("completed", terminalState?.finishReason)
    }

    @Test
    fun `collect stream accepts legacy blank request ids`() = runTest {
        val runtime = FlowRuntimeGateway(
            flowFactory = { request ->
                flow {
                    emit(ChatStreamEvent.Started(requestId = "", startedAtEpochMs = 1L))
                    emit(ChatStreamEvent.TokenDelta(requestId = "", token = "legacy ", accumulatedText = "legacy"))
                    emit(
                        ChatStreamEvent.Completed(
                            requestId = "",
                            response = ChatResponse(
                                sessionId = request.sessionId,
                                modelId = "auto",
                                text = "legacy response",
                                firstTokenLatencyMs = 1L,
                                totalLatencyMs = 2L,
                            ),
                            finishReason = "completed",
                        ),
                    )
                }
            },
        )
        val coordinator = ChatStreamCoordinator(
            terminalWatchdogGraceMs = 10L,
            sendElapsedUpdateIntervalMs = 5L,
        )
        val reducer = StreamStateReducer(requestTimeoutMs = 200L)
        var terminalState: StreamTerminalState? = null

        coordinator.collectStream(
            runtimeGateway = runtime,
            request = request("req-legacy"),
            requestTimeoutMs = 200L,
            streamReducer = reducer,
            sendStartedAtMs = System.currentTimeMillis(),
            onEvent = { _, _ -> },
            onElapsed = { _, _ -> },
            onBeforeTerminal = { },
            onTerminal = { terminal -> terminalState = terminal },
        )

        assertNotNull(terminalState)
        assertEquals("completed", terminalState?.finishReason)
        assertEquals("legacy response", terminalState?.responseText)
    }
}

private fun request(requestId: String): StreamUserMessageRequest {
    return StreamUserMessageRequest(
        sessionId = SessionId("session-1"),
        requestId = requestId,
        userText = "hello",
        taskType = "short_text",
        deviceState = DeviceState(80, 3, 8),
    )
}

private class FlowRuntimeGateway(
    private val flowFactory: (StreamUserMessageRequest) -> Flow<ChatStreamEvent>,
) : RuntimeGateway {
    var cancelByRequestCalls: Int = 0

    override fun createSession(): SessionId = SessionId("session-1")

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return flowFactory(request)
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        cancelByRequestCalls += 1
        return true
    }

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        return ToolExecutionResult.Success("tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        return ImageAnalysisResult.Success("image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) = Unit

    override fun getRoutingMode(): RoutingMode = RoutingMode.AUTO

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = null

    override fun supportsGpuOffload(): Boolean = false
}
