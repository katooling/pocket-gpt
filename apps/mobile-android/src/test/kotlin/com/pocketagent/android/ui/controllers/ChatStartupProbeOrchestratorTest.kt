package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeResult
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.PreparedChatStream
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStartupProbeOrchestratorTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `cancel resets running probe state back to idle`() = runTest(dispatcher) {
        var state = ChatUiState()
        val runtime = FakeStartupRuntime()
        val startupFlow = ChatStartupFlow(
            runtimeGateway = runtime,
            startupProbeController = object : StartupProbeController() {
                override suspend fun runStartupChecks(
                    runtimeGateway: ChatRuntimeService,
                    ioDispatcher: CoroutineDispatcher,
                    timeoutMs: Long,
                ): List<String> {
                    awaitCancellation()
                }
            },
            startupReadinessCoordinator = StartupReadinessCoordinator(),
            ioDispatcher = dispatcher,
            runtimeStartupProbeTimeoutMs = 1_000L,
            nativeRuntimeLibraryPackaged = true,
        )
        val orchestrator = ChatStartupProbeOrchestrator(
            scope = this,
            ioDispatcher = dispatcher,
            runtimeGateway = runtime,
            startupFlow = startupFlow,
            startupReadinessCoordinator = StartupReadinessCoordinator(),
            updateState = { transform -> state = transform(state) },
            onPersist = {},
            onProbeApplied = {},
            log = { _, _, _, _ -> },
        )

        orchestrator.launch()
        runCurrent()

        assertEquals(StartupProbeState.RUNNING, state.runtime.startupProbeState)
        assertEquals(ModelRuntimeStatus.LOADING, state.runtime.modelRuntimeStatus)

        orchestrator.cancel()
        advanceUntilIdle()

        assertEquals(StartupProbeState.IDLE, state.runtime.startupProbeState)
        assertEquals(ModelRuntimeStatus.NOT_READY, state.runtime.modelRuntimeStatus)
    }
}

private class FakeStartupRuntime : ChatRuntimeService {
    override fun createSession(): SessionId = SessionId("session-1")

    override fun streamPreparedChat(prepared: PreparedChatStream): Flow<ChatStreamEvent> {
        error("not used")
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        error("not used")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        error("not used")
    }

    override fun exportDiagnostics(): String = "diag"

    override fun setRoutingMode(mode: RoutingMode) = Unit

    override fun getRoutingMode(): RoutingMode = RoutingMode.AUTO

    override fun runStartupChecks(): List<String> = emptyList()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = null

    override fun supportsGpuOffload(): Boolean = false

    override fun gpuOffloadStatus(): GpuProbeResult {
        return GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.UNKNOWN,
            detail = "gpu_offload_unsupported",
        )
    }
}
