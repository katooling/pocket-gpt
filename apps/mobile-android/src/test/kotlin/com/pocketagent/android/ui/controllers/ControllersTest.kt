package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControllersTest {
    @Test
    fun `chat send controller delegates tool execution to runtime gateway`() = runTest {
        val runtime = RecordingRuntimeGateway()
        val controller = ChatSendController(runtimeGateway = runtime, ioDispatcher = Dispatchers.IO)

        val result = controller.runTool("calculator", """{"expression":"2+2"}""")

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("calculator", runtime.lastToolName)
        assertEquals("""{"expression":"2+2"}""", runtime.lastToolJsonArgs)
    }

    @Test
    fun `chat send controller delegates image analysis to runtime gateway`() = runTest {
        val runtime = RecordingRuntimeGateway()
        val controller = ChatSendController(runtimeGateway = runtime, ioDispatcher = Dispatchers.IO)

        val result = controller.analyzeImage("/tmp/a.jpg", "describe")

        assertTrue(result is ImageAnalysisResult.Success)
        assertEquals("/tmp/a.jpg", runtime.lastImagePath)
        assertEquals("describe", runtime.lastImagePrompt)
    }

    @Test
    fun `startup probe controller returns timeout marker when startup checks exceed timeout`() = runTest {
        val runtime = RecordingRuntimeGateway(
            startupBehavior = {
                val started = System.currentTimeMillis()
                while ((System.currentTimeMillis() - started) < 40L) {
                    Thread.onSpinWait()
                }
                listOf("late")
            },
        )
        val controller = StartupProbeController()

        val checks = controller.runStartupChecks(
            runtimeGateway = runtime,
            ioDispatcher = Dispatchers.IO,
            timeoutMs = 5L,
        )

        assertTrue(checks.single().contains("timed out"))
    }

    @Test
    fun `startup probe controller converts unexpected startup exception into check message`() = runTest {
        val runtime = RecordingRuntimeGateway(
            startupBehavior = {
                error("startup crash")
            },
        )
        val controller = StartupProbeController()

        val checks = controller.runStartupChecks(
            runtimeGateway = runtime,
            ioDispatcher = Dispatchers.IO,
            timeoutMs = 1_000L,
        )

        assertEquals(1, checks.size)
        assertTrue(checks.single().contains("failed unexpectedly"))
    }

}

private class RecordingRuntimeGateway(
    private val startupBehavior: () -> List<String> = { emptyList() },
) : ChatRuntimeService {
    var lastToolName: String? = null
    var lastToolJsonArgs: String? = null
    var lastImagePath: String? = null
    var lastImagePrompt: String? = null

    override fun createSession(): SessionId = SessionId("session-1")
    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> = emptyFlow()

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        lastToolName = toolName
        lastToolJsonArgs = jsonArgs
        return ToolExecutionResult.Success("tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        lastImagePath = imagePath
        lastImagePrompt = prompt
        return ImageAnalysisResult.Success("image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) = Unit

    override fun getRoutingMode(): RoutingMode = RoutingMode.AUTO

    override fun runStartupChecks(): List<String> = startupBehavior()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = null

    override fun supportsGpuOffload(): Boolean = false
}
