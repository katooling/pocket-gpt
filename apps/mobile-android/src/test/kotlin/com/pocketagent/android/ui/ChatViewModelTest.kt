package com.pocketagent.android.ui

import com.pocketagent.android.runtime.RuntimeGateway
import com.pocketagent.android.ui.controllers.ChatSendFlow
import com.pocketagent.android.ui.controllers.DeviceStateProvider
import com.pocketagent.android.ui.controllers.StartupProbeController
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.SessionPersistence
import com.pocketagent.android.ui.state.SessionStateLoadResult
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ImageFailure
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.StreamUserMessageRequest
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.ToolFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
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
        advanceUntilIdle()

        viewModel.onComposerChanged("hello ui")
        viewModel.sendMessage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val activeSession = state.activeSession!!
        assertTrue(activeSession.messages.any { it.role == MessageRole.USER && it.content == "hello ui" })
        assertTrue(activeSession.messages.any { it.role == MessageRole.ASSISTANT && it.content.contains("response for hello ui") })
        assertEquals("auto", state.runtime.activeModelId)
        assertTrue(persistence.savedStates.isNotEmpty())
        assertEquals(null, state.runtime.lastErrorCode)
    }

    @Test
    fun `natural language calculator prompt executes local tool path`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("calculate 4*9")
        viewModel.sendMessage()
        advanceUntilIdle()

        val messages = viewModel.uiState.value.activeSession!!.messages
        assertTrue(messages.any { it.role == MessageRole.USER && it.content == "calculate 4*9" })
        assertTrue(messages.any { it.role == MessageRole.ASSISTANT && it.toolName == "calculator" })
    }

    @Test
    fun `onboarding state defaults visible and persists when completed`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showOnboarding)
        assertEquals(FirstSessionStage.ONBOARDING, viewModel.uiState.value.firstSessionStage)

        viewModel.completeOnboarding()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showOnboarding)
        assertEquals(FirstSessionStage.READY_TO_CHAT, viewModel.uiState.value.firstSessionStage)
        assertTrue(persistence.savedStates.last().onboardingCompleted)
    }

    @Test
    fun `advanced controls are available on first launch`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(
            runtimeFacade = RecordingRuntimeFacade(),
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.advancedUnlocked)
        viewModel.setAdvancedSheetOpen(true)
        viewModel.setToolDialogOpen(true)

        assertTrue(viewModel.uiState.value.isAdvancedSheetOpen)
        assertTrue(viewModel.uiState.value.isToolDialogOpen)
    }

    @Test
    fun `simple-first progress still advances milestones when advanced controls are already available`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.completeOnboarding()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.advancedUnlocked)

        viewModel.onComposerChanged("first question")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.firstAnswerCompleted)
        assertFalse(viewModel.uiState.value.followUpCompleted)
        assertTrue(viewModel.uiState.value.advancedUnlocked)
        assertEquals(FirstSessionStage.FIRST_ANSWER_DONE, viewModel.uiState.value.firstSessionStage)

        viewModel.onComposerChanged("follow up question")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.firstAnswerCompleted)
        assertTrue(viewModel.uiState.value.followUpCompleted)
        assertTrue(viewModel.uiState.value.advancedUnlocked)
        assertEquals(FirstSessionStage.FOLLOW_UP_DONE, viewModel.uiState.value.firstSessionStage)
        assertFalse(viewModel.uiState.value.firstSessionTelemetryEvents.any { it.eventName == "advanced_unlocked" })
    }

    @Test
    fun `get ready action moves stage to get ready and records telemetry`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.completeOnboarding()
        advanceUntilIdle()
        viewModel.onGetReadyTapped()
        advanceUntilIdle()

        assertEquals(FirstSessionStage.GET_READY, viewModel.uiState.value.firstSessionStage)
        assertTrue(viewModel.uiState.value.firstSessionTelemetryEvents.any { it.eventName == "get_ready_started" })
    }

    @Test
    fun `stream token updates do not persist state on every token`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade(
            streamTokens = List(40) { "tok$it " },
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("long stream")
        viewModel.sendMessage()
        advanceUntilIdle()

        // bootstrap + send start + completed persist path should stay bounded.
        assertTrue(persistence.savedStates.size <= 6)
    }

    @Test
    fun `send flow uses injected device telemetry provider for routing request`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val expectedDeviceState = DeviceState(
            batteryPercent = 22,
            thermalLevel = 7,
            ramClassGb = 6,
        )
        val sendFlow = ChatSendFlow(
            runtimeGenerationTimeoutMs = 0L,
            deviceStateProvider = DeviceStateProvider { expectedDeviceState },
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
            sendFlow = sendFlow,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("telemetry check")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(expectedDeviceState, runtime.lastStreamRequest?.deviceState)
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

    @Test
    fun `recoverable persisted state corruption is surfaced with deterministic ui code`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(
            runtimeFacade = RecordingRuntimeFacade(),
            sessionPersistence = CorruptLoadPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val initialRuntimeState = viewModel.uiState.value.runtime
        assertEquals("UI-SESSION-001", initialRuntimeState.lastErrorCode)
        assertEquals(com.pocketagent.android.ui.state.StartupProbeState.BLOCKED, initialRuntimeState.startupProbeState)

        viewModel.refreshRuntimeReadiness()
        advanceUntilIdle()

        val recoveredRuntimeState = viewModel.uiState.value.runtime
        assertEquals(com.pocketagent.android.ui.state.StartupProbeState.READY, recoveredRuntimeState.startupProbeState)
        assertEquals(null, recoveredRuntimeState.lastErrorCode)
    }

    @Test
    fun `session switch preserves per-session timeline state`() = runTest(dispatcher) {
        val persistence = RecordingPersistence()
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = persistence,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("session one message")
        viewModel.sendMessage()
        advanceUntilIdle()
        val firstSessionId = viewModel.uiState.value.activeSession!!.id

        viewModel.createSession()
        val secondSessionId = viewModel.uiState.value.activeSession!!.id
        viewModel.onComposerChanged("session two message")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.switchSession(firstSessionId)
        val firstSession = viewModel.uiState.value.activeSession!!
        assertTrue(firstSession.messages.any { it.content == "session one message" })
        assertFalse(firstSession.messages.any { it.content == "session two message" })

        viewModel.switchSession(secondSessionId)
        val secondSession = viewModel.uiState.value.activeSession!!
        assertTrue(secondSession.messages.any { it.content == "session two message" })
    }

    @Test
    fun `attach image success appends image user message and assistant response`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.attachImage("/tmp/test-image.jpg")
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.kind == MessageKind.IMAGE && it.role == MessageRole.USER })
        assertTrue(active.messages.any { it.kind == MessageKind.IMAGE && it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun `attach image failure shows system error message`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(failImage = true)
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.attachImage("/tmp/bad-image.jpg")
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-RUNTIME-001") })
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `image validation error maps to deterministic ui code`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(returnImageValidationError = true)
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.attachImage("/tmp/invalid.tiff")
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-IMG-VAL-001") })
        assertEquals("UI-IMG-VAL-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `tool request success and failures are rendered in timeline`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.runTool("calculator", """{"expression":"4*9"}""")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.activeSession!!.messages.any { it.role == MessageRole.ASSISTANT && it.content.contains("tool:calculator") })

        runtime.failTool = true
        viewModel.runTool("calculator", """{"expression":"4*9"}""")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.activeSession!!.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-RUNTIME-001") })
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)

        runtime.failTool = false
        runtime.returnToolValidationError = true
        viewModel.runTool("calculator", """{"expression":"4*9"}""")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.activeSession!!.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-TOOL-SCHEMA-001") })
        assertEquals("UI-TOOL-SCHEMA-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `startup checks are mapped to startup ui error code`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Missing runtime model(s): qwen"),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )

        advanceUntilIdle()
        assertEquals("UI-STARTUP-001", viewModel.uiState.value.runtime.lastErrorCode)
        assertTrue(viewModel.uiState.value.runtime.lastErrorUserMessage?.contains("Runtime setup is incomplete") == true)
    }

    @Test
    fun `send path blocks when runtime startup probe is not ready`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Missing runtime model(s): qwen"),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello while blocked")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertFalse(active.messages.any { it.role == MessageRole.USER && it.content == "hello while blocked" })
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-STARTUP-001") })
        assertEquals("UI-STARTUP-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `send message timeout maps to deterministic runtime error`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTerminal = StreamTerminal.CANCELLED_TIMEOUT,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
            runtimeGenerationTimeoutMs = 50L,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("ola how you doin")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-RUNTIME-001") })
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("timed out") })
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)
        assertFalse(viewModel.uiState.value.composer.isSending)
    }

    @Test
    fun `cancelled terminal event preserves partial output and records terminal metadata`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTokens = listOf("partial "),
            streamTerminal = StreamTerminal.CANCELLED_MANUAL,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("cancel test")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        val assistant = active.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.contains("partial") }
        val terminalSystem = active.messages.lastOrNull { it.role == MessageRole.SYSTEM && it.finishReason == "cancelled" }

        assertTrue(assistant != null)
        assertEquals("cancelled", assistant?.finishReason)
        assertTrue(assistant?.terminalEventSeen == true)
        assertTrue(assistant?.requestId?.isNotBlank() == true)
        assertTrue(terminalSystem != null)
        assertEquals(assistant?.requestId, terminalSystem?.requestId)
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)
        assertFalse(viewModel.uiState.value.composer.isSending)
    }

    @Test
    fun `failed terminal event records normalized finish reason`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTokens = listOf("chunk "),
            streamTerminal = StreamTerminal.FAILED,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("failure test")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        val assistant = active.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.contains("chunk") }
        val terminalSystem = active.messages.lastOrNull { it.role == MessageRole.SYSTEM && it.finishReason == "failed:jni_utf8_stream_error" }

        assertTrue(assistant != null)
        assertEquals("failed:jni_utf8_stream_error", assistant?.finishReason)
        assertTrue(assistant?.terminalEventSeen == true)
        assertTrue(terminalSystem != null)
        assertEquals(assistant?.requestId, terminalSystem?.requestId)
        assertEquals("UI-RUNTIME-001", viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `startup probe timeout maps to blocked timeout startup state`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val startupProbeController = TimeoutStartupProbeController()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
            runtimeStartupProbeTimeoutMs = 50L,
            startupProbeController = startupProbeController,
        )
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals("UI-STARTUP-001", runtimeState.lastErrorCode)
        assertEquals(
            "Startup checks timed out. Runtime readiness is unknown; refresh checks before sending.",
            runtimeState.modelStatusDetail,
        )
        assertEquals(com.pocketagent.android.ui.state.ModelRuntimeStatus.NOT_READY, runtimeState.modelRuntimeStatus)
        assertTrue(runtimeState.startupChecks.any { it.contains("timed out", ignoreCase = true) })
        assertEquals(com.pocketagent.android.ui.state.StartupProbeState.BLOCKED_TIMEOUT, runtimeState.startupProbeState)
    }

    @Test
    fun `startup probe timeout blocks send path`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTerminal = StreamTerminal.COMPLETED,
        )
        val startupProbeController = TimeoutStartupProbeController()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
            runtimeStartupProbeTimeoutMs = 50L,
            runtimeGenerationTimeoutMs = 500L,
            startupProbeController = startupProbeController,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello degraded mode")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertFalse(active.messages.any { it.role == MessageRole.USER && it.content == "hello degraded mode" })
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-STARTUP-001") })
    }

    @Test
    fun `startup timeout does not enter stream flow`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamTerminal = StreamTerminal.COMPLETED,
            streamDelayMs = 100L,
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
            runtimeStartupProbeTimeoutMs = 50L,
            runtimeGenerationTimeoutMs = 500L,
            startupProbeController = TimeoutStartupProbeController(),
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello virtual time")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertFalse(active.messages.any { it.role == MessageRole.USER && it.content == "hello virtual time" })
        assertTrue(active.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("UI-STARTUP-001") })
    }

    @Test
    fun `optional model warning maps to ready state with warning and no startup error`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Optional runtime model unavailable: qwen3.5-2b-q4."),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals(com.pocketagent.android.ui.state.StartupProbeState.READY, runtimeState.startupProbeState)
        assertEquals(com.pocketagent.android.ui.state.ModelRuntimeStatus.READY, runtimeState.modelRuntimeStatus)
        assertEquals(null, runtimeState.lastErrorCode)
        assertEquals(1, runtimeState.startupWarnings.size)
        assertTrue(runtimeState.modelStatusDetail?.contains("model ready") == true)
    }

    @Test
    fun `send path stays enabled when startup checks only report optional model warning`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Optional runtime model unavailable: qwen3.5-2b-q4."),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("hello optional warning")
        viewModel.sendMessage()
        advanceUntilIdle()

        val active = viewModel.uiState.value.activeSession!!
        assertTrue(active.messages.any { it.role == MessageRole.USER && it.content == "hello optional warning" })
        assertEquals(null, viewModel.uiState.value.runtime.lastErrorCode)
    }

    @Test
    fun `battery profile uses extended adaptive timeout`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.setPerformanceProfile(RuntimePerformanceProfile.BATTERY)
        viewModel.onComposerChanged("hello with 2b")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(480_000L, runtime.lastStreamRequest?.requestTimeoutMs)
    }

    @Test
    fun `cancel active send delegates request scoped cancellation`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            streamDelayMs = 500L,
            streamTokens = listOf("a "),
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
            runtimeGenerationTimeoutMs = 1_000L,
        )
        advanceUntilIdle()

        viewModel.onComposerChanged("cancel me")
        viewModel.sendMessage()
        viewModel.cancelActiveSend()
        advanceUntilIdle()

        assertTrue(runtime.cancelledRequestIds.isNotEmpty())
    }

    @Test
    fun `routing mode and diagnostics export update runtime and timeline`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade()
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.setRoutingMode(RoutingMode.QWEN_2B)
        viewModel.exportDiagnostics()
        advanceUntilIdle()

        assertEquals(RoutingMode.QWEN_2B, viewModel.uiState.value.runtime.routingMode)
        assertTrue(viewModel.uiState.value.activeSession!!.messages.any { it.role == MessageRole.SYSTEM && it.content.contains("diag=ok") })
    }

    @Test
    fun `refresh runtime readiness updates backend and startup state`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )

        viewModel.refreshRuntimeReadiness()
        advanceUntilIdle()

        assertEquals("NATIVE_JNI", viewModel.uiState.value.runtime.runtimeBackend)
        assertEquals(null, viewModel.uiState.value.runtime.lastErrorCode)
        assertTrue(runtime.cancelledSessions.isNotEmpty())
    }

    @Test
    fun `refresh runtime readiness does not mask blocking startup failures with override text`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(
            startupChecks = listOf("Missing runtime model(s): qwen"),
            runtimeBackend = "NATIVE_JNI",
        )
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.refreshRuntimeReadiness(statusDetailOverride = "verified and active")
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals("UI-STARTUP-001", runtimeState.lastErrorCode)
        assertEquals("Missing runtime model(s): qwen", runtimeState.modelStatusDetail)
    }

    @Test
    fun `refresh runtime readiness applies override text only when startup checks pass`() = runTest(dispatcher) {
        val runtime = RecordingRuntimeFacade(runtimeBackend = "NATIVE_JNI")
        val viewModel = ChatViewModel(
            runtimeFacade = runtime,
            sessionPersistence = RecordingPersistence(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.refreshRuntimeReadiness(statusDetailOverride = "verified and active")
        advanceUntilIdle()

        val runtimeState = viewModel.uiState.value.runtime
        assertEquals(null, runtimeState.lastErrorCode)
        assertEquals("verified and active", runtimeState.modelStatusDetail)
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

private class CorruptLoadPersistence : SessionPersistence {
    private var persisted = PersistedChatState()

    override fun loadState(): PersistedChatState = persisted

    override fun loadStateResult(): SessionStateLoadResult {
        return SessionStateLoadResult.RecoverableCorruption(
            resetState = PersistedChatState(),
            code = "CHAT_STATE_CORRUPT_JSON",
            userMessage = "Saved chat state was corrupted and reset.",
            technicalDetail = "code=CHAT_STATE_CORRUPT_JSON;backup=chat-state-1;error=parse",
        )
    }

    override fun saveState(state: PersistedChatState) {
        persisted = state
    }
}

private class RecordingRuntimeFacade(
    private val failImage: Boolean = false,
    private val returnImageValidationError: Boolean = false,
    private val startupChecks: List<String> = emptyList(),
    private val streamTokens: List<String> = listOf("stream ", "token "),
    private val streamDelayMs: Long = 0L,
    private val streamTerminal: StreamTerminal = StreamTerminal.COMPLETED,
    private val runtimeBackend: String? = null,
) : RuntimeGateway {
    private var sessionCounter = 0
    private var routingMode: RoutingMode = RoutingMode.AUTO
    var failTool: Boolean = false
    var returnToolValidationError: Boolean = false
    val restoredTurns = mutableListOf<Pair<SessionId, List<Turn>>>()
    val cancelledSessions = mutableListOf<SessionId>()
    val cancelledRequestIds = mutableListOf<String>()
    var lastStreamRequest: StreamUserMessageRequest? = null

    override fun createSession(): SessionId {
        sessionCounter += 1
        return SessionId("session-$sessionCounter")
    }

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> = flow {
        lastStreamRequest = request
        emit(
            ChatStreamEvent.Started(
                requestId = request.requestId,
                startedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        if (streamDelayMs > 0L) {
            delay(streamDelayMs)
        }
        val builder = StringBuilder()
        streamTokens.forEach { token ->
            builder.append(token)
            emit(
                ChatStreamEvent.TokenDelta(
                    requestId = request.requestId,
                    token = token,
                    accumulatedText = builder.toString().trim(),
                ),
            )
        }
        when (streamTerminal) {
            StreamTerminal.COMPLETED -> emit(
                ChatStreamEvent.Completed(
                    requestId = request.requestId,
                    response = ChatResponse(
                        sessionId = request.sessionId,
                        modelId = "auto",
                        text = "response for ${request.userText}",
                        firstTokenLatencyMs = 25,
                        totalLatencyMs = 75,
                    ),
                    finishReason = "completed",
                    firstTokenMs = 25,
                    completionMs = 75,
                ),
            )

            StreamTerminal.CANCELLED_TIMEOUT -> emit(
                ChatStreamEvent.Cancelled(
                    requestId = request.requestId,
                    reason = "timeout",
                    terminalEventSeen = true,
                    firstTokenMs = if (streamTokens.isNotEmpty()) 25 else null,
                    completionMs = 75,
                ),
            )

            StreamTerminal.CANCELLED_MANUAL -> emit(
                ChatStreamEvent.Cancelled(
                    requestId = request.requestId,
                    reason = "cancelled",
                    terminalEventSeen = true,
                    firstTokenMs = if (streamTokens.isNotEmpty()) 25 else null,
                    completionMs = 75,
                ),
            )

            StreamTerminal.FAILED -> emit(
                ChatStreamEvent.Failed(
                    requestId = request.requestId,
                    errorCode = "jni_utf8_stream_error",
                    message = "utf8 stream failure",
                    terminalEventSeen = true,
                    firstTokenMs = if (streamTokens.isNotEmpty()) 25 else null,
                    completionMs = 75,
                ),
            )
        }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean {
        cancelledSessions += sessionId
        return true
    }

    override fun cancelGenerationByRequest(requestId: String): Boolean {
        cancelledRequestIds += requestId
        return true
    }

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        if (returnToolValidationError) {
            return ToolExecutionResult.Failure(
                ToolFailure.Validation(
                    code = "invalid_field_value",
                    userMessage = "That tool request was rejected for safety.",
                    technicalDetail = "Field 'expression' has disallowed characters.",
                ),
            )
        }
        if (failTool) {
            error("simulated tool failure")
        }
        return ToolExecutionResult.Success("tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        if (returnImageValidationError) {
            return ImageAnalysisResult.Failure(
                ImageFailure.Validation(
                    code = "unsupported_extension",
                    userMessage = "That image could not be processed. Use a supported file and try again.",
                    technicalDetail = "extension 'tiff' is not supported",
                ),
            )
        }
        if (failImage) {
            error("simulated image failure")
        }
        return ImageAnalysisResult.Success("image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        routingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = routingMode

    override fun runStartupChecks(): List<String> = startupChecks

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        restoredTurns += sessionId to turns
    }

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String? = runtimeBackend

    override fun supportsGpuOffload(): Boolean = false
}

private enum class StreamTerminal {
    COMPLETED,
    CANCELLED_TIMEOUT,
    CANCELLED_MANUAL,
    FAILED,
}

private class TimeoutStartupProbeController : StartupProbeController() {
    override suspend fun runStartupChecks(
        runtimeGateway: RuntimeGateway,
        ioDispatcher: CoroutineDispatcher,
        timeoutMs: Long,
    ): List<String> {
        val timeoutSeconds = (timeoutMs / 1000L).coerceAtLeast(1L)
        return listOf("Startup checks timed out after ${timeoutSeconds}s.")
    }
}
