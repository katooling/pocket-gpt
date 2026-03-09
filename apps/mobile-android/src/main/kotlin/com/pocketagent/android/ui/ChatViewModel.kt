package com.pocketagent.android.ui

import android.util.Log
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeResult
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.runtime.RuntimeGateway
import com.pocketagent.android.ui.controllers.ChatPersistenceFlow
import com.pocketagent.android.ui.controllers.ChatStreamCoordinator
import com.pocketagent.android.ui.controllers.ChatPersistenceCoordinator
import com.pocketagent.android.ui.controllers.DeviceStateProvider
import com.pocketagent.android.ui.controllers.ChatSendFlow
import com.pocketagent.android.ui.controllers.ChatSendController
import com.pocketagent.android.ui.controllers.SendReducer
import com.pocketagent.android.ui.controllers.ChatStartupFlow
import com.pocketagent.android.ui.controllers.StartupProbeController
import com.pocketagent.android.ui.controllers.StartupProbeOutcome
import com.pocketagent.android.ui.controllers.StartupReadinessCoordinator
import com.pocketagent.android.ui.controllers.TimelineProjector
import com.pocketagent.android.ui.controllers.ToolLoopOutcome
import com.pocketagent.android.ui.controllers.ToolLoopUseCase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.FirstSessionStage
import com.pocketagent.android.ui.state.FirstSessionTelemetryEvent
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedInteractionMessage
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.PersistedToolCall
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.SessionPersistence
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.RuntimePerformanceProfile
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatViewModel(
    private val runtimeFacade: RuntimeGateway,
    sessionPersistence: SessionPersistence,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runtimeGenerationTimeoutMs: Long = 0L,
    private val runtimeStartupProbeTimeoutMs: Long = DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS,
    private val sendController: ChatSendController = ChatSendController(runtimeFacade, ioDispatcher),
    private val streamCoordinator: ChatStreamCoordinator = ChatStreamCoordinator(),
    private val startupProbeController: StartupProbeController = StartupProbeController(),
    private val startupReadinessCoordinator: StartupReadinessCoordinator = StartupReadinessCoordinator(
        runtimeProfile = resolveModelRuntimeProfile(isDebugBuild = BuildConfig.DEBUG),
    ),
    private val persistenceCoordinator: ChatPersistenceCoordinator = ChatPersistenceCoordinator(sessionPersistence),
    private val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
    private val timelineProjector: TimelineProjector = TimelineProjector(),
    private val persistenceFlow: ChatPersistenceFlow = ChatPersistenceFlow(persistenceCoordinator),
    private val startupFlow: ChatStartupFlow = ChatStartupFlow(
        runtimeGateway = runtimeFacade,
        startupProbeController = startupProbeController,
        startupReadinessCoordinator = startupReadinessCoordinator,
        ioDispatcher = ioDispatcher,
        runtimeStartupProbeTimeoutMs = runtimeStartupProbeTimeoutMs,
        nativeRuntimeLibraryPackaged = BuildConfig.NATIVE_RUNTIME_LIBRARY_PACKAGED,
        timelineProjector = timelineProjector,
    ),
    private val sendFlow: ChatSendFlow = ChatSendFlow(
        runtimeGenerationTimeoutMs = runtimeGenerationTimeoutMs,
        deviceStateProvider = deviceStateProvider,
    ),
    private val sendReducer: SendReducer = SendReducer(),
    private val toolLoopUseCase: ToolLoopUseCase = ToolLoopUseCase(sendController),
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private var startupProbeJob: Job? = null
    private var gpuProbeRefreshJob: Job? = null
    @Volatile
    private var latestStartupProbeToken: Long = 0L
    @Volatile
    private var inFlightStartupProbeDetail: String? = null
    @Volatile
    private var activeSendRequestId: String? = null

    init {
        bootstrapState()
    }

    fun onComposerChanged(text: String) {
        _uiState.update { state ->
            state.copy(composer = state.composer.copy(text = text))
        }
    }

    fun sendMessage() {
        val snapshot = _uiState.value
        val activeSession = snapshot.activeSession ?: return
        val prompt = snapshot.composer.text.trim()
        if (prompt.isBlank() || snapshot.composer.isSending) {
            return
        }

        if (!sendFlow.isRuntimeReadyForSend(snapshot.runtime)) {
            val uiError = startupFlow.startupBlockError(snapshot.runtime)
            appendSystemMessage(
                sessionId = activeSession.id,
                content = formatUserFacingError(uiError),
            )
            _uiState.update { state ->
                state.copy(
                    runtime = state.runtime.copy(
                        modelRuntimeStatus = if (state.runtime.startupProbeState == StartupProbeState.RUNNING) {
                            ModelRuntimeStatus.LOADING
                        } else {
                            ModelRuntimeStatus.NOT_READY
                        },
                    ).withUiError(uiError),
                )
            }
            persistState()
            return
        }
        val performanceConfig = sendFlow.resolvePerformanceConfig(
            profile = snapshot.runtime.performanceProfile,
            gpuEnabled = snapshot.runtime.gpuAccelerationEnabled,
            gpuLayers = snapshot.runtime.gpuMaxQualifiedLayers.coerceAtLeast(0),
        )
        val requestTimeoutMs = sendFlow.resolveRequestTimeoutMs(performanceConfig)
        val userMessage = createMessage(
            role = MessageRole.USER,
            content = prompt,
            kind = MessageKind.TEXT,
        )

        updateActiveSession(activeSession.id) { session ->
            val updatedMessages = session.messages + userMessage
            session.copy(
                messages = updatedMessages,
                updatedAtEpochMs = System.currentTimeMillis(),
                title = deriveSessionTitle(updatedMessages),
            )
        }
        _uiState.update { state ->
            state.copy(
                composer = ComposerUiState(text = "", isSending = true),
                runtime = sendReducer.onSendStarted(
                    runtime = state.runtime,
                    toolDriven = false,
                ),
            )
        }
        persistState()
        val sessionAfterUserMessage = _uiState.value.activeSession ?: return

        val assistantMessageId = newMessageId(prefix = "assistant-stream")
        val requestId = newRequestId()
        val previousResponseId = timelineProjector.latestAssistantRequestId(sessionAfterUserMessage)
        val transcriptMessages = timelineProjector.toTranscript(sessionAfterUserMessage)
        activeSendRequestId = requestId
        val assistantPlaceholder = MessageUiModel(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            content = "",
            timestampEpochMs = System.currentTimeMillis(),
            kind = MessageKind.TEXT,
            isStreaming = true,
            requestId = requestId,
            finishReason = null,
            terminalEventSeen = false,
            interaction = PersistedInteractionMessage(
                role = MessageRole.ASSISTANT.name,
                parts = listOf(PersistedInteractionPart(type = "text", text = "")),
                metadata = mapOf("state" to "streaming"),
            ),
        )
        updateActiveSession(activeSession.id) { session ->
            session.copy(
                messages = session.messages + assistantPlaceholder,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }

        viewModelScope.launch(ioDispatcher) {
            var pendingStreamingText: String? = null
            var lastStreamingUiUpdateAtMs = 0L
            val sendStartedAtMs = System.currentTimeMillis()
            val streamReducer = StreamStateReducer(requestTimeoutMs = requestTimeoutMs)

            fun flushPendingStreamingText(force: Boolean = false, triggerToken: String? = null) {
                val text = pendingStreamingText?.trim().orEmpty()
                if (text.isBlank()) {
                    return
                }
                val now = System.currentTimeMillis()
                val forceByToken = triggerToken?.let { token ->
                    token.contains('\n') || token.trim().endsWith(".") || token.trim().endsWith("!") || token.trim().endsWith("?")
                } ?: false
                val canFlush = force || lastStreamingUiUpdateAtMs == 0L ||
                    (now - lastStreamingUiUpdateAtMs) >= STREAM_UI_UPDATE_MIN_INTERVAL_MS ||
                    forceByToken
                if (!canFlush) {
                    return
                }
                updateStreamingMessage(
                    sessionId = activeSession.id,
                    messageId = assistantMessageId,
                    text = text,
                )
                lastStreamingUiUpdateAtMs = now
            }

            fun finalizeWithRuntimeError(
                uiError: UiError,
                terminalReason: String,
                terminalRequestId: String = requestId,
                terminalEventSeen: Boolean = true,
            ) {
                val partialStreamingText = messageContent(
                    sessionId = activeSession.id,
                    messageId = assistantMessageId,
                ).orEmpty().trim()
                if (partialStreamingText.isNotBlank()) {
                    finalizeStreamingMessage(
                        sessionId = activeSession.id,
                        messageId = assistantMessageId,
                        finalText = partialStreamingText,
                        role = MessageRole.ASSISTANT,
                        requestId = terminalRequestId,
                        finishReason = terminalReason,
                        terminalEventSeen = terminalEventSeen,
                    )
                    appendSystemMessage(
                        sessionId = activeSession.id,
                        content = formatUserFacingError(uiError),
                        requestId = terminalRequestId,
                        finishReason = terminalReason,
                        terminalEventSeen = terminalEventSeen,
                    )
                } else {
                    finalizeStreamingMessage(
                        sessionId = activeSession.id,
                        messageId = assistantMessageId,
                        finalText = formatUserFacingError(uiError),
                        role = MessageRole.SYSTEM,
                        requestId = terminalRequestId,
                        finishReason = terminalReason,
                        terminalEventSeen = terminalEventSeen,
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(isSending = false),
                        runtime = state.runtime
                            .copy(
                                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                                modelStatusDetail = uiError.userMessage,
                                sendElapsedMs = null,
                                sendSlowState = null,
                            )
                            .withUiError(uiError),
                    )
                }
                persistState()
            }

            fun finalizeFromTerminal(terminal: StreamTerminalState) {
                if (terminal.uiError != null) {
                    finalizeWithRuntimeError(
                        uiError = terminal.uiError,
                        terminalReason = terminal.finishReason,
                        terminalRequestId = terminal.requestId,
                        terminalEventSeen = terminal.terminalEventSeen,
                    )
                    return
                }
                val finalText = terminal.responseText?.trim().orEmpty()
                finalizeStreamingMessage(
                    sessionId = activeSession.id,
                    messageId = assistantMessageId,
                    finalText = finalText,
                    requestId = terminal.requestId,
                    finishReason = terminal.finishReason,
                    terminalEventSeen = terminal.terminalEventSeen,
                )
                val effectiveFirstToken = terminal.firstTokenMs
                val effectiveCompletion = terminal.completionMs ?: (System.currentTimeMillis() - sendStartedAtMs)
                val effectivePrefill = effectiveFirstToken
                val effectiveDecode = if (effectiveFirstToken != null) {
                    (effectiveCompletion - effectiveFirstToken).coerceAtLeast(0L)
                } else {
                    null
                }
                val tokensPerSecEstimate = if (!finalText.isBlank() && effectiveDecode != null && effectiveDecode > 0L) {
                    val approxTokens = finalText.split(Regex("\\s+")).count { it.isNotBlank() }
                    if (approxTokens > 0) {
                        approxTokens.toDouble() / (effectiveDecode.toDouble() / 1000.0)
                    } else {
                        null
                    }
                } else {
                    null
                }
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(isSending = false),
                        runtime = state.runtime.copy(
                            runtimeBackend = runtimeFacade.runtimeBackend(),
                            startupProbeState = StartupProbeState.READY,
                            modelRuntimeStatus = ModelRuntimeStatus.READY,
                            modelStatusDetail = startupFlow.readyStatusDetail(runtimeFacade.runtimeBackend()),
                            activeModelId = terminal.responseModelId,
                            lastFirstTokenLatencyMs = effectiveFirstToken,
                            lastTotalLatencyMs = effectiveCompletion,
                            lastPrefillMs = effectivePrefill,
                            lastDecodeMs = effectiveDecode,
                            lastTokensPerSec = tokensPerSecEstimate,
                            sendElapsedMs = null,
                            sendSlowState = null,
                        ).clearError(),
                    )
                }
                persistState()
                maybeAdvanceAfterAssistantResponse()
            }

            streamCoordinator.collectStream(
                runtimeGateway = runtimeFacade,
                request = sendFlow.buildStreamChatRequest(
                    sessionId = activeSession.id,
                    requestId = requestId,
                    messages = transcriptMessages,
                    taskTypeHint = prompt,
                    performanceConfig = performanceConfig,
                    requestTimeoutMs = requestTimeoutMs,
                    previousResponseId = previousResponseId,
                ),
                requestTimeoutMs = requestTimeoutMs,
                streamReducer = streamReducer,
                sendStartedAtMs = sendStartedAtMs,
                onEvent = { event, nextState ->
                    when (event) {
                        is ChatStreamEvent.TokenDelta -> {
                            pendingStreamingText = nextState.accumulatedText
                            flushPendingStreamingText(triggerToken = event.token)
                        }
                        is ChatStreamEvent.Delta -> when (val delta = event.delta) {
                            is ChatStreamDelta.TextDelta -> {
                                pendingStreamingText = nextState.accumulatedText
                                flushPendingStreamingText(triggerToken = delta.text)
                            }
                        }
                        else -> Unit
                    }
                    val detail = sendReducer.statusDetailForEvent(event)
                    if (!detail.isNullOrBlank()) {
                        _uiState.update { state ->
                            state.copy(
                                runtime = state.runtime.copy(
                                    modelStatusDetail = detail,
                                ),
                            )
                        }
                    }
                },
                onElapsed = { elapsed, slowState ->
                    _uiState.update { state ->
                        state.copy(
                            runtime = state.runtime.copy(
                                sendElapsedMs = elapsed,
                                sendSlowState = slowState,
                            ),
                        )
                    }
                },
                onBeforeTerminal = {
                    flushPendingStreamingText(force = true)
                },
                onTerminal = { terminal ->
                    finalizeFromTerminal(terminal)
                },
            )
            activeSendRequestId = null
        }
    }

    fun cancelActiveSend() {
        val requestId = activeSendRequestId ?: return
        runtimeFacade.cancelGenerationByRequest(requestId)
        _uiState.update { state ->
            state.copy(
                runtime = state.runtime.copy(
                    modelStatusDetail = "Cancelling generation...",
                ),
            )
        }
    }

    fun createSession() {
        val newSessionId = runtimeFacade.createSession().value
        val now = System.currentTimeMillis()
        val session = ChatSessionUiModel(
            id = newSessionId,
            title = "New chat",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            messages = emptyList(),
        )
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions + session,
                activeSessionId = session.id,
                isSessionDrawerOpen = false,
            )
        }
        persistState()
    }

    fun switchSession(sessionId: String) {
        _uiState.update { state ->
            state.copy(activeSessionId = sessionId, isSessionDrawerOpen = false)
        }
        persistState()
    }

    fun deleteSession(sessionId: String) {
        runtimeFacade.deleteSession(SessionId(sessionId))
        _uiState.update { state ->
            val remaining = state.sessions.filterNot { it.id == sessionId }
            val nextActive = when {
                remaining.isEmpty() -> null
                state.activeSessionId == sessionId -> remaining.last().id
                else -> state.activeSessionId
            }
            state.copy(
                sessions = remaining,
                activeSessionId = nextActive,
            )
        }
        if (_uiState.value.sessions.isEmpty()) {
            createSession()
            return
        }
        persistState()
    }

    fun attachImage(imagePath: String) {
        val snapshot = _uiState.value
        val activeSession = snapshot.activeSession ?: return
        if (!sendFlow.isRuntimeReadyForSend(snapshot.runtime)) {
            val uiError = startupFlow.startupBlockError(snapshot.runtime)
            appendSystemMessage(
                sessionId = activeSession.id,
                content = formatUserFacingError(uiError),
            )
            _uiState.update { state ->
                state.copy(runtime = state.runtime.withUiError(uiError))
            }
            persistState()
            return
        }
        val imageMessage = createMessage(
            role = MessageRole.USER,
            content = "Analyze attached image",
            kind = MessageKind.IMAGE,
            imagePath = imagePath,
        )
        updateActiveSession(activeSession.id) { session ->
            session.copy(
                messages = session.messages + imageMessage,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
        _uiState.update {
            it.copy(
                composer = it.composer.copy(isSending = true),
                runtime = it.runtime.copy(
                    modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                    modelStatusDetail = "Loading runtime model...",
                ).clearError(),
            )
        }
        persistState()

        viewModelScope.launch(ioDispatcher) {
            runCatching {
                sendController.analyzeImage(imagePath = imagePath, prompt = "Describe this image.")
            }.onSuccess { result ->
                val mappedError = UiErrorMapper.fromImageResult(result)
                if (mappedError != null) {
                    appendSystemMessage(
                        sessionId = activeSession.id,
                        content = formatUserFacingError(mappedError),
                    )
                    _uiState.update { state ->
                        state.copy(
                            composer = state.composer.copy(isSending = false),
                            runtime = state.runtime.copy(
                                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                                modelStatusDetail = mappedError.userMessage,
                            ).withUiError(mappedError),
                        )
                    }
                    persistState()
                    return@onSuccess
                }
                val responseText = when (result) {
                    is com.pocketagent.runtime.ImageAnalysisResult.Success -> result.content
                    is com.pocketagent.runtime.ImageAnalysisResult.Failure -> {
                        result.failure.technicalDetail ?: result.failure.userMessage
                    }
                }
                val assistant = createMessage(
                    role = MessageRole.ASSISTANT,
                    content = responseText,
                    kind = MessageKind.IMAGE,
                )
                updateActiveSession(activeSession.id) { session ->
                    session.copy(
                        messages = session.messages + assistant,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(isSending = false),
                        runtime = state.runtime.copy(
                                runtimeBackend = runtimeFacade.runtimeBackend(),
                            modelRuntimeStatus = ModelRuntimeStatus.READY,
                            modelStatusDetail = "Image analysis completed",
                        ).clearError(),
                    )
                }
                persistState()
            }.onFailure { error ->
                val uiError = UiErrorMapper.runtimeFailure(error.message ?: "Image analysis failed.")
                appendSystemMessage(
                    sessionId = activeSession.id,
                    content = formatUserFacingError(uiError),
                )
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(isSending = false),
                        runtime = state.runtime.copy(
                            modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                            modelStatusDetail = uiError.userMessage,
                        ).withUiError(uiError),
                    )
                }
                persistState()
            }
        }
    }

    fun runTool(toolName: String, jsonArgs: String) {
        val activeSession = _uiState.value.activeSession ?: return
        val request = createMessage(
            role = MessageRole.USER,
            content = "Run tool: $toolName",
            kind = MessageKind.TEXT,
        )
        val toolCallId = newToolCallId()
        val assistantToolCall = createMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            kind = MessageKind.TOOL,
            toolName = toolName,
            toolArgsJson = jsonArgs,
            toolCallId = toolCallId,
            toolCallStatus = PersistedToolCallStatus.RUNNING,
        )
        updateActiveSession(activeSession.id) { session ->
            session.copy(
                messages = session.messages + request + assistantToolCall,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
        _uiState.update {
            it.copy(
                composer = it.composer.copy(isSending = true),
                runtime = it.runtime.copy(
                    modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                    modelStatusDetail = "Preparing local tool...",
                ).clearError(),
            )
        }
        persistState()

        executeToolCommand(
            sessionId = activeSession.id,
            toolName = toolName,
            jsonArgs = jsonArgs,
            toolCallId = toolCallId,
        )
    }

    fun exportDiagnostics() {
        val activeSession = _uiState.value.activeSession ?: return
        viewModelScope.launch(ioDispatcher) {
            runCatching { runtimeFacade.exportDiagnostics() }
                .onSuccess { diagnostics ->
                    val diagnosticsMessage = createMessage(
                        role = MessageRole.SYSTEM,
                        content = diagnostics,
                        kind = MessageKind.DIAGNOSTIC,
                    )
                    updateActiveSession(activeSession.id) { session ->
                        session.copy(
                            messages = session.messages + diagnosticsMessage,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                    _uiState.update { state ->
                        state.copy(runtime = state.runtime.clearError())
                    }
                    persistState()
                }
                .onFailure { error ->
                    val uiError = UiErrorMapper.runtimeFailure(error.message ?: "Diagnostics export failed.")
                    appendSystemMessage(
                        sessionId = activeSession.id,
                        content = formatUserFacingError(uiError),
                    )
                    _uiState.update { state ->
                        state.copy(runtime = state.runtime.withUiError(uiError))
                    }
                    persistState()
                }
        }
    }

    fun setRoutingMode(mode: RoutingMode) {
        val effectiveMode = coerceSupportedRoutingMode(mode)
        if (_uiState.value.runtime.routingMode == effectiveMode) {
            return
        }
        runtimeFacade.setRoutingMode(effectiveMode)
        _uiState.update { state ->
            state.copy(runtime = state.runtime.copy(routingMode = effectiveMode))
        }
        persistState()
    }

    fun setPerformanceProfile(profile: RuntimePerformanceProfile) {
        if (_uiState.value.runtime.performanceProfile == profile) {
            return
        }
        _uiState.update { state ->
            state.copy(
                runtime = state.runtime.copy(
                    performanceProfile = profile,
                    modelStatusDetail = performanceProfileStatusDetail(
                        profile = profile,
                        gpuEnabled = state.runtime.gpuAccelerationEnabled,
                        gpuSupported = state.runtime.gpuAccelerationSupported,
                    ),
                ),
            )
        }
        persistState()
    }

    fun setGpuAccelerationEnabled(enabled: Boolean) {
        val snapshot = _uiState.value.runtime
        val supported = snapshot.gpuAccelerationSupported
        val effective = enabled && supported
        val detail = if (enabled && !supported) {
            when (snapshot.gpuProbeStatus) {
                com.pocketagent.android.runtime.GpuProbeStatus.PENDING ->
                    "Validating GPU support... keeping CPU until probe is qualified."
                com.pocketagent.android.runtime.GpuProbeStatus.FAILED ->
                    "GPU acceleration unavailable (${snapshot.gpuProbeFailureReason ?: "probe_failed"}). Using CPU."
                else ->
                    "GPU acceleration is unavailable on this build/device. Using CPU."
            }
        } else {
            performanceProfileStatusDetail(
                profile = snapshot.performanceProfile,
                gpuEnabled = effective,
                gpuSupported = supported,
            )
        }
        if (
            snapshot.gpuAccelerationEnabled == effective &&
            snapshot.modelStatusDetail == detail
        ) {
            return
        }
        _uiState.update { state ->
            state.copy(
                runtime = state.runtime.copy(
                    gpuAccelerationEnabled = effective,
                    modelStatusDetail = detail,
                ),
            )
        }
        persistState()
    }

    fun setSessionDrawerOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isSessionDrawerOpen = isOpen) }
    }

    fun setAdvancedSheetOpen(isOpen: Boolean) {
        if (_uiState.value.isAdvancedSheetOpen == isOpen) {
            return
        }
        _uiState.update {
            it.copy(
                isAdvancedSheetOpen = isOpen,
                showAdvancedUnlockCue = if (isOpen) false else it.showAdvancedUnlockCue,
            )
        }
        persistState()
    }

    fun setToolDialogOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isToolDialogOpen = isOpen) }
    }

    fun setPrivacySheetOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isPrivacySheetOpen = isOpen) }
    }

    fun prefillComposer(text: String) {
        _uiState.update { state ->
            state.copy(
                composer = state.composer.copy(text = text),
                isToolDialogOpen = false,
            )
        }
    }

    fun nextOnboardingPage() {
        _uiState.update { state ->
            state.copy(onboardingPage = (state.onboardingPage + 1).coerceAtMost(ONBOARDING_LAST_PAGE))
        }
    }

    fun completeOnboarding() {
        _uiState.update { state ->
            state.copy(
                showOnboarding = false,
                onboardingPage = ONBOARDING_LAST_PAGE,
                firstSessionStage = if (sendFlow.isRuntimeReadyForSend(state.runtime)) {
                    FirstSessionStage.READY_TO_CHAT
                } else {
                    FirstSessionStage.GET_READY
                },
            )
        }
        recordFirstSessionEventOnce(TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED)
        persistState()
    }

    fun skipOnboarding() {
        completeOnboarding()
    }

    fun refreshRuntimeReadiness(statusDetailOverride: String? = null) {
        activeSendRequestId?.let { requestId ->
            runtimeFacade.cancelGenerationByRequest(requestId)
        }
        _uiState.value.activeSessionId?.let { sessionId ->
            runtimeFacade.cancelGeneration(SessionId(sessionId))
        }
        launchStartupProbe(statusDetailOverride)
    }

    fun onGetReadyTapped() {
        _uiState.update { state ->
            state.copy(
                firstSessionStage = FirstSessionStage.GET_READY,
            )
        }
        recordFirstSessionEventOnce(TELEMETRY_EVENT_GET_READY_STARTED)
        persistState()
    }

    fun onFirstAnswerCompleted() {
        _uiState.update { state ->
            state.copy(
                firstAnswerCompleted = true,
                firstSessionStage = FirstSessionStage.FIRST_ANSWER_DONE,
            )
        }
        recordFirstSessionEventOnce(TELEMETRY_EVENT_FIRST_ANSWER_COMPLETED)
        persistState()
    }

    fun onFollowUpCompleted() {
        _uiState.update { state ->
            state.copy(
                followUpCompleted = true,
                firstSessionStage = FirstSessionStage.FOLLOW_UP_DONE,
            )
        }
        recordFirstSessionEventOnce(TELEMETRY_EVENT_FOLLOW_UP_COMPLETED)
        persistState()
    }

    fun onAdvancedUnlocked() {
        _uiState.update { state ->
            state.copy(
                advancedUnlocked = true,
                showAdvancedUnlockCue = true,
                firstSessionStage = FirstSessionStage.ADVANCED_UNLOCKED,
            )
        }
        recordFirstSessionEventOnce(TELEMETRY_EVENT_ADVANCED_UNLOCKED)
        persistState()
    }

    private fun bootstrapState() {
        val loadedState = persistenceFlow.loadBootstrapState()
        val bootstrapResult = startupFlow.bootstrap(loadedState)
        _uiState.value = bootstrapResult.state
        refreshGpuProbeStatusIfPending()
        ensureSimpleFirstEnteredTelemetryIfNeeded()
        if (bootstrapResult.shouldPersist) {
            persistState()
        }
        if (bootstrapResult.shouldRunStartupProbe) {
            launchStartupProbe()
        }
    }

    private fun launchStartupProbe(statusDetailOverride: String? = null) {
        val normalizedDetail = statusDetailOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (startupProbeJob?.isActive == true && inFlightStartupProbeDetail == normalizedDetail) {
            logStartupProbe("coalesced", latestStartupProbeToken, normalizedDetail)
            return
        }
        val probeToken = nextStartupProbeToken(normalizedDetail)
        startupProbeJob?.cancel()
        startupProbeJob = viewModelScope.launch(ioDispatcher) {
            logStartupProbe("started", probeToken, normalizedDetail)
            try {
                _uiState.update { state -> startupFlow.markProbeRunning(state) }
                val outcome = try {
                    startupFlow.evaluateStartup(statusDetailOverride = normalizedDetail)
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        throw error
                    }
                    logStartupProbe("failed", probeToken, normalizedDetail, error)
                    buildFallbackProbeOutcome(error)
                }
                if (!isLatestStartupProbe(probeToken)) {
                    logStartupProbe("stale_discarded", probeToken, normalizedDetail)
                    return@launch
                }
                _uiState.update { state -> startupFlow.applyProbeOutcome(state, outcome) }
                refreshGpuProbeStatusIfPending()
                persistState()
                logStartupProbe("completed", probeToken, normalizedDetail)
            } catch (cancelled: CancellationException) {
                logStartupProbe("cancelled", probeToken, normalizedDetail)
                throw cancelled
            } finally {
                if (isLatestStartupProbe(probeToken)) {
                    inFlightStartupProbeDetail = null
                }
            }
        }
    }

    private fun nextStartupProbeToken(statusDetailOverride: String?): Long {
        val next = latestStartupProbeToken + 1L
        latestStartupProbeToken = next
        inFlightStartupProbeDetail = statusDetailOverride
        return next
    }

    private fun isLatestStartupProbe(probeToken: Long): Boolean = latestStartupProbeToken == probeToken

    private fun buildFallbackProbeOutcome(error: Throwable): StartupProbeOutcome {
        val fallbackCheck = "Startup checks failed unexpectedly: ${error.message ?: error::class.simpleName.orEmpty()}"
        val runtimeBackend = runtimeFacade.runtimeBackend()
        val gpuProbe = runCatching { runtimeFacade.gpuOffloadStatus() }.getOrElse {
            com.pocketagent.android.runtime.GpuProbeResult(
                status = com.pocketagent.android.runtime.GpuProbeStatus.FAILED,
                failureReason = com.pocketagent.android.runtime.GpuProbeFailureReason.UNKNOWN,
                detail = "fallback_probe_status_failed:${it.message ?: it::class.simpleName}",
            )
        }
        return StartupProbeOutcome(
            startupChecks = listOf(fallbackCheck),
            runtimeBackend = runtimeBackend,
            gpuProbeResult = gpuProbe,
            readinessDecision = startupReadinessCoordinator.decide(
                startupChecks = listOf(fallbackCheck),
                runtimeBackend = runtimeBackend,
                statusDetailOverride = null,
            ),
        )
    }

    private fun refreshGpuProbeStatusIfPending() {
        if (_uiState.value.runtime.gpuProbeStatus != GpuProbeStatus.PENDING) {
            gpuProbeRefreshJob?.cancel()
            gpuProbeRefreshJob = null
            return
        }
        if (gpuProbeRefreshJob?.isActive == true) {
            return
        }
        gpuProbeRefreshJob = viewModelScope.launch(ioDispatcher) {
            repeat(GPU_PROBE_REFRESH_MAX_ATTEMPTS) {
                if (!isActive) {
                    return@launch
                }
                val nextProbe = runCatching { runtimeFacade.gpuOffloadStatus() }.getOrElse {
                    GpuProbeResult(
                        status = GpuProbeStatus.FAILED,
                        failureReason = GpuProbeFailureReason.UNKNOWN,
                        detail = "gpu_probe_refresh_failed:${it.message ?: it::class.simpleName}",
                    )
                }
                val changed = updateRuntimeGpuProbeState(nextProbe)
                if (changed) {
                    persistState()
                }
                if (nextProbe.status != GpuProbeStatus.PENDING) {
                    return@launch
                }
                delay(GPU_PROBE_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun updateRuntimeGpuProbeState(probe: GpuProbeResult): Boolean {
        var changed = false
        _uiState.update { state ->
            val gpuSupported = probe.status == GpuProbeStatus.QUALIFIED && probe.maxStableGpuLayers > 0
            val runtime = state.runtime
            val nextRuntime = runtime.copy(
                gpuAccelerationSupported = gpuSupported,
                gpuAccelerationEnabled = runtime.gpuAccelerationEnabled && gpuSupported,
                gpuProbeStatus = probe.status,
                gpuProbeFailureReason = probe.failureReason?.name,
                gpuMaxQualifiedLayers = probe.maxStableGpuLayers,
            )
            changed = runtime != nextRuntime
            if (!changed) {
                state
            } else {
                state.copy(runtime = nextRuntime)
            }
        }
        return changed
    }

    private fun logStartupProbe(
        phase: String,
        probeToken: Long,
        statusDetailOverride: String?,
        error: Throwable? = null,
    ) {
        val message = "STARTUP_PROBE|phase=$phase|token=$probeToken|detail=${statusDetailOverride.orEmpty()}"
        runCatching {
            if (error == null) {
                Log.i(LOG_TAG, message)
            } else {
                Log.w(LOG_TAG, message, error)
            }
        }
    }

    private fun updateStreamingMessage(sessionId: String, messageId: String, text: String) {
        updateActiveSession(sessionId) { session ->
            val updatedMessages = session.messages.map { message ->
                if (message.id != messageId) {
                    message
                } else {
                    message.copy(
                        content = text,
                        isStreaming = true,
                        interaction = (message.interaction ?: PersistedInteractionMessage(
                            role = message.role.name,
                            parts = listOf(PersistedInteractionPart(type = "text", text = "")),
                        )).copy(
                            parts = listOf(PersistedInteractionPart(type = "text", text = text)),
                            metadata = (message.interaction?.metadata ?: emptyMap()) + ("state" to "streaming"),
                        ),
                    )
                }
            }
            session.copy(
                messages = updatedMessages,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun finalizeStreamingMessage(
        sessionId: String,
        messageId: String,
        finalText: String,
        role: MessageRole = MessageRole.ASSISTANT,
        requestId: String? = null,
        finishReason: String? = null,
        terminalEventSeen: Boolean = true,
    ) {
        updateActiveSession(sessionId) { session ->
            val updatedMessages = session.messages.map { message ->
                if (message.id != messageId) {
                    message
                } else {
                    message.copy(
                        role = role,
                        content = finalText,
                        isStreaming = false,
                        timestampEpochMs = System.currentTimeMillis(),
                        requestId = requestId ?: message.requestId,
                        finishReason = finishReason,
                        terminalEventSeen = terminalEventSeen,
                        interaction = (message.interaction ?: PersistedInteractionMessage(
                            role = role.name,
                            parts = listOf(PersistedInteractionPart(type = "text", text = finalText)),
                        )).copy(
                            role = role.name,
                            parts = listOf(PersistedInteractionPart(type = "text", text = finalText)),
                            metadata = (message.interaction?.metadata ?: emptyMap()) + ("state" to "final"),
                        ),
                    )
                }
            }
            session.copy(
                messages = updatedMessages,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun appendSystemMessage(
        sessionId: String,
        content: String,
        requestId: String? = null,
        finishReason: String? = null,
        terminalEventSeen: Boolean = false,
    ) {
        updateActiveSession(sessionId) { session ->
            session.copy(
                messages = session.messages + createMessage(
                    role = MessageRole.SYSTEM,
                    content = content,
                    kind = MessageKind.TEXT,
                    requestId = requestId,
                    finishReason = finishReason,
                    terminalEventSeen = terminalEventSeen,
                ),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun messageContent(
        sessionId: String,
        messageId: String,
    ): String? {
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return null
        return session.messages.firstOrNull { it.id == messageId }?.content
    }

    private fun updateActiveSession(
        sessionId: String,
        transform: (ChatSessionUiModel) -> ChatSessionUiModel,
    ) {
        _uiState.update { state ->
            val updatedSessions = state.sessions.map { session ->
                if (session.id == sessionId) {
                    transform(session)
                } else {
                    session
                }
            }
            state.copy(sessions = updatedSessions)
        }
    }

    private fun persistState() {
        persistenceFlow.saveState(_uiState.value)
    }

    private fun executeToolCommand(
        sessionId: String,
        toolName: String,
        jsonArgs: String,
        toolCallId: String,
    ) {
        viewModelScope.launch(ioDispatcher) {
            when (val outcome = toolLoopUseCase.execute(toolName = toolName, jsonArgs = jsonArgs)) {
                is ToolLoopOutcome.Success -> {
                    updateToolCallStatus(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        status = PersistedToolCallStatus.COMPLETED,
                    )
                    val toolMessage = createMessage(
                        role = MessageRole.TOOL,
                        content = outcome.content,
                        kind = MessageKind.TOOL,
                        toolName = toolName,
                        toolCallId = toolCallId,
                    )
                    updateActiveSession(sessionId) { session ->
                        session.copy(
                            messages = session.messages + toolMessage,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                    _uiState.update { state ->
                        state.copy(
                            composer = state.composer.copy(isSending = false),
                            runtime = state.runtime.copy(
                                runtimeBackend = runtimeFacade.runtimeBackend(),
                                modelRuntimeStatus = ModelRuntimeStatus.READY,
                                modelStatusDetail = "Local tool completed",
                            ).clearError(),
                        )
                    }
                    persistState()
                }
                is ToolLoopOutcome.Failure -> {
                    updateToolCallStatus(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        status = PersistedToolCallStatus.FAILED,
                    )
                    appendSystemMessage(
                        sessionId = sessionId,
                        content = formatUserFacingError(outcome.uiError),
                    )
                    _uiState.update { state ->
                        state.copy(
                            composer = state.composer.copy(isSending = false),
                            runtime = state.runtime.copy(
                                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                                modelStatusDetail = outcome.uiError.userMessage,
                            ).withUiError(outcome.uiError),
                        )
                    }
                    persistState()
                }
            }
        }
    }

    private fun updateToolCallStatus(
        sessionId: String,
        toolCallId: String,
        status: PersistedToolCallStatus,
    ) {
        updateActiveSession(sessionId) { session ->
            session.copy(
                messages = session.messages.map { message ->
                    val interaction = message.interaction ?: return@map message
                    if (interaction.toolCalls.none { toolCall -> toolCall.id == toolCallId }) {
                        return@map message
                    }
                    message.copy(
                        interaction = interaction.copy(
                            toolCalls = interaction.toolCalls.map { toolCall ->
                                if (toolCall.id == toolCallId) {
                                    toolCall.copy(status = status)
                                } else {
                                    toolCall
                                }
                            },
                        ),
                    )
                },
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun performanceProfileStatusDetail(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
        gpuSupported: Boolean,
    ): String {
        val profileLabel = profile.name.lowercase().replaceFirstChar { it.uppercase() }
        val gpuLabel = when {
            gpuEnabled && gpuSupported -> "GPU enabled"
            gpuEnabled && !gpuSupported -> "GPU unavailable, using CPU"
            else -> "GPU off"
        }
        return "Speed & Battery: $profileLabel ($gpuLabel)"
    }

    private fun deriveSessionTitle(messages: List<MessageUiModel>): String {
        val firstUserMessage = messages.firstOrNull { it.role == MessageRole.USER } ?: return "New chat"
        val normalized = firstUserMessage.content
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(TITLE_MAX_CHARS)
        return normalized.ifBlank { "New chat" }
    }

    private fun createMessage(
        role: MessageRole,
        content: String,
        kind: MessageKind,
        imagePath: String? = null,
        toolName: String? = null,
        toolArgsJson: String? = null,
        toolCallId: String? = null,
        toolCallStatus: PersistedToolCallStatus = PersistedToolCallStatus.PENDING,
        requestId: String? = null,
        finishReason: String? = null,
        terminalEventSeen: Boolean = false,
    ): MessageUiModel {
        val assistantToolCall = if (
            role == MessageRole.ASSISTANT &&
            kind == MessageKind.TOOL &&
            !toolName.isNullOrBlank() &&
            !toolArgsJson.isNullOrBlank()
        ) {
            listOf(
                PersistedToolCall(
                    id = toolCallId ?: "toolcall-${UUID.randomUUID()}",
                    name = toolName,
                    argumentsJson = toolArgsJson,
                    status = toolCallStatus,
                ),
            )
        } else {
            emptyList()
        }
        val legacyUserToolCall = if (
            role == MessageRole.USER &&
            kind == MessageKind.TOOL &&
            !toolName.isNullOrBlank() &&
            !toolArgsJson.isNullOrBlank()
        ) {
            listOf(
                PersistedToolCall(
                    id = toolCallId ?: "toolcall-${UUID.randomUUID()}",
                    name = toolName,
                    argumentsJson = toolArgsJson,
                    status = toolCallStatus,
                ),
            )
        } else {
            emptyList()
        }
        val interactionToolCall = if (assistantToolCall.isNotEmpty()) assistantToolCall else legacyUserToolCall
        val resolvedToolCallId = when {
            role == MessageRole.TOOL -> toolCallId
            interactionToolCall.isNotEmpty() -> interactionToolCall.first().id
            else -> toolCallId
        }
        val interaction = PersistedInteractionMessage(
            role = role.name,
            parts = listOf(PersistedInteractionPart(type = "text", text = content)),
            toolCalls = interactionToolCall,
            toolCallId = resolvedToolCallId,
            metadata = buildMap {
                put("kind", kind.name)
                imagePath?.let { put("imagePath", it) }
                toolName?.let { put("toolName", it) }
                resolvedToolCallId?.let { put("toolCallId", it) }
            },
        )
        return MessageUiModel(
            id = newMessageId(prefix = "msg-${role.name.lowercase()}"),
            role = role,
            content = content,
            timestampEpochMs = System.currentTimeMillis(),
            kind = kind,
            imagePath = imagePath,
            toolName = toolName,
            isStreaming = false,
            requestId = requestId,
            finishReason = finishReason,
            terminalEventSeen = terminalEventSeen,
            interaction = interaction,
        )
    }

    private fun maybeAdvanceAfterAssistantResponse() {
        val snapshot = _uiState.value
        if (snapshot.showOnboarding) {
            return
        }
        if (!snapshot.firstAnswerCompleted) {
            onFirstAnswerCompleted()
            return
        }
        if (!snapshot.followUpCompleted) {
            onFollowUpCompleted()
            if (!snapshot.advancedUnlocked) {
                onAdvancedUnlocked()
            }
        }
    }

    private fun ensureSimpleFirstEnteredTelemetryIfNeeded() {
        val state = _uiState.value
        if (state.showOnboarding && state.firstSessionTelemetryEvents.any { it.eventName == TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED }) {
            return
        }
        if (!state.showOnboarding) {
            recordFirstSessionEventOnce(TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED)
        }
    }

    private fun recordFirstSessionEventOnce(eventName: String) {
        var changed = false
        _uiState.update { state ->
            val updatedEvents = addTelemetryEventIfMissing(
                events = state.firstSessionTelemetryEvents,
                eventName = eventName,
            )
            changed = updatedEvents.size != state.firstSessionTelemetryEvents.size
            state.copy(
                firstSessionTelemetryEvents = updatedEvents,
            )
        }
        if (changed) {
            persistState()
        }
    }

    private fun newRequestId(): String = "req-${UUID.randomUUID()}"

    private fun newToolCallId(): String = "toolcall-${UUID.randomUUID()}"

    private fun newMessageId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    companion object {
        private const val LOG_TAG = "ChatViewModel"
        private const val TITLE_MAX_CHARS = 42
        private const val ONBOARDING_LAST_PAGE = 2
        private const val DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS = 30_000L
        private const val GPU_PROBE_REFRESH_INTERVAL_MS = 700L
        private const val GPU_PROBE_REFRESH_MAX_ATTEMPTS = 160
        private const val STREAM_UI_UPDATE_MIN_INTERVAL_MS = 80L
        private const val TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED = "simple_first_entered"
        private const val TELEMETRY_EVENT_GET_READY_STARTED = "get_ready_started"
        private const val TELEMETRY_EVENT_FIRST_ANSWER_COMPLETED = "first_answer_completed"
        private const val TELEMETRY_EVENT_FOLLOW_UP_COMPLETED = "follow_up_completed"
        private const val TELEMETRY_EVENT_ADVANCED_UNLOCKED = "advanced_unlocked"
    }
}

internal fun addTelemetryEventIfMissing(
    events: List<FirstSessionTelemetryEvent>,
    eventName: String,
): List<FirstSessionTelemetryEvent> {
    if (events.any { it.eventName == eventName }) {
        return events
    }
    return (events + FirstSessionTelemetryEvent(eventName = eventName, eventTimeUtc = Instant.now().toString()))
        .takeLast(64)
}

internal fun RuntimeUiState.clearError(): RuntimeUiState {
    return copy(
        lastErrorCode = null,
        lastErrorUserMessage = null,
        lastErrorTechnicalDetail = null,
        lastError = null,
    )
}

internal fun RuntimeUiState.withUiError(error: UiError?): RuntimeUiState {
    if (error == null) {
        return clearError()
    }
    return copy(
        lastErrorCode = error.code,
        lastErrorUserMessage = error.userMessage,
        lastErrorTechnicalDetail = error.technicalDetail,
        lastError = error.technicalDetail ?: error.userMessage,
    )
}

private fun formatUserFacingError(error: UiError): String {
    return "${error.userMessage} (${error.code})"
}

class ChatViewModelFactory(
    private val runtimeFacade: RuntimeGateway,
    private val sessionPersistence: SessionPersistence,
    private val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(
                runtimeFacade = runtimeFacade,
                sessionPersistence = sessionPersistence,
                deviceStateProvider = deviceStateProvider,
            ) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}
