package com.pocketagent.runtime

import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ModelResidencyPolicy
import com.pocketagent.runtime.PerformanceRuntimeConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMvpRuntimeFacadeTest {
    @Test
    fun `stream user message emits token and completed events`() = runTest {
        val container = FakeRuntimeContainer()
        val facade = DefaultMvpRuntimeFacade(container)
        val request = StreamUserMessageRequest(
            sessionId = SessionId("session-1"),
            userText = "hello",
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            maxTokens = 64,
        )

        val events = facade.streamUserMessage(request).toList()

        assertEquals(4, events.size)
        assertTrue(events[0] is ChatStreamEvent.Started)
        assertTrue(events[1] is ChatStreamEvent.TokenDelta)
        assertTrue(events[2] is ChatStreamEvent.TokenDelta)
        assertTrue(events[3] is ChatStreamEvent.Completed)
        assertEquals("hello", (events[1] as ChatStreamEvent.TokenDelta).accumulatedText)
        assertEquals("hello world", (events[2] as ChatStreamEvent.TokenDelta).accumulatedText)
        assertEquals("response", (events[3] as ChatStreamEvent.Completed).response.text)
        assertEquals("hello", container.lastUserText)
        assertEquals(64, container.lastMaxTokens)
    }

    @Test
    fun `delegates runtime operations to container`() {
        val container = FakeRuntimeContainer()
        val facade = DefaultMvpRuntimeFacade(container)

        assertEquals("session-1", facade.createSession().value)
        assertEquals("tool:calculator", facade.runTool("calculator", """{"expression":"1+2"}"""))
        assertEquals("image:/tmp/a.jpg", facade.analyzeImage("/tmp/a.jpg", "describe"))
        assertEquals("diag=ok", facade.exportDiagnostics())
        facade.setRoutingMode(RoutingMode.QWEN_2B)
        assertEquals(RoutingMode.QWEN_2B, facade.getRoutingMode())
        assertEquals(listOf("check"), facade.runStartupChecks())

        val turns = listOf(Turn(role = "user", content = "hello", timestampEpochMs = 1))
        facade.restoreSession(SessionId("session-1"), turns)
        assertEquals(1, container.restoreCalls)
        assertTrue(facade.deleteSession(SessionId("session-1")))
        assertEquals(1, container.deleteCalls)
    }

    @Test
    fun `stream user message emits cancelled event on runtime cancellation`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationCancelledException(requestId = "req-cancel")
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = StreamUserMessageRequest(
            sessionId = SessionId("session-1"),
            requestId = "req-cancel",
            userText = "hello",
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            maxTokens = 64,
        )

        val events = facade.streamUserMessage(request).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is ChatStreamEvent.Started)
        assertTrue(events[1] is ChatStreamEvent.Cancelled)
        assertEquals("cancelled", (events[1] as ChatStreamEvent.Cancelled).reason)
    }

    @Test
    fun `stream user message emits failed event with bridge error code`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationFailureException(
                message = "utf8 stream failure",
                errorCode = "JNI_UTF8_STREAM_ERROR",
            )
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = StreamUserMessageRequest(
            sessionId = SessionId("session-1"),
            requestId = "req-fail",
            userText = "hello",
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            maxTokens = 64,
        )

        val events = facade.streamUserMessage(request).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is ChatStreamEvent.Started)
        assertTrue(events[1] is ChatStreamEvent.Failed)
        assertEquals("jni_utf8_stream_error", (events[1] as ChatStreamEvent.Failed).errorCode)
    }

    @Test
    fun `terminal failure does not trigger duplicate cancel from awaitClose`() = runTest {
        val container = FakeRuntimeContainer().apply {
            sendError = RuntimeGenerationFailureException(
                message = "runtime failure",
                errorCode = "JNI_RUNTIME_ERROR",
            )
        }
        val facade = DefaultMvpRuntimeFacade(container)
        val request = StreamUserMessageRequest(
            sessionId = SessionId("session-1"),
            requestId = "req-fail-nocancel",
            userText = "hello",
            taskType = "short_text",
            deviceState = DeviceState(80, 3, 8),
            maxTokens = 64,
        )

        val events = facade.streamUserMessage(request).toList()

        assertEquals(2, events.size)
        assertEquals(0, container.cancelByRequestCalls)
        assertEquals(0, container.cancelBySessionCalls)
    }
}

private class FakeRuntimeContainer : RuntimeContainer {
    private var currentRoutingMode: RoutingMode = RoutingMode.AUTO
    var lastUserText: String = ""
    var lastMaxTokens: Int = 0
    var restoreCalls: Int = 0
    var deleteCalls: Int = 0
    var sendError: Throwable? = null
    var cancelByRequestCalls: Int = 0
    var cancelBySessionCalls: Int = 0

    override fun createSession(): SessionId = SessionId("session-1")

    override fun sendUserMessage(
        sessionId: SessionId,
        userText: String,
        taskType: String,
        deviceState: DeviceState,
        maxTokens: Int,
        keepModelLoaded: Boolean,
        onToken: (String) -> Unit,
        requestTimeoutMs: Long,
        requestId: String,
        performanceConfig: PerformanceRuntimeConfig,
        residencyPolicy: ModelResidencyPolicy,
    ): ChatResponse {
        sendError?.let { throw it }
        lastUserText = userText
        lastMaxTokens = maxTokens
        onToken("hello ")
        onToken("world ")
        return ChatResponse(
            sessionId = sessionId,
            modelId = "auto",
            text = "response",
            firstTokenLatencyMs = 42,
            totalLatencyMs = 75,
        )
    }

    override fun runTool(toolName: String, jsonArgs: String): String = "tool:$toolName"

    override fun analyzeImage(imagePath: String, prompt: String): String = "image:$imagePath"

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        currentRoutingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = currentRoutingMode

    override fun runStartupChecks(): List<String> = listOf("check")

    override fun cancelGeneration(sessionId: SessionId): Boolean {
        cancelBySessionCalls += 1
        return true
    }

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        cancelByRequestCalls += 1
        return true
    }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        restoreCalls += 1
    }

    override fun deleteSession(sessionId: SessionId): Boolean {
        deleteCalls += 1
        return true
    }
}
