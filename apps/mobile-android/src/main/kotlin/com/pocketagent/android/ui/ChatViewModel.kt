package com.pocketagent.android.ui

import com.pocketagent.android.BuildConfig
import com.pocketagent.android.runtime.RuntimeGateway
import com.pocketagent.android.ui.controllers.ChatStreamCoordinator
import com.pocketagent.android.ui.controllers.ChatPersistenceCoordinator
import com.pocketagent.android.ui.controllers.ChatSendController
import com.pocketagent.android.ui.controllers.StartupProbeController
import com.pocketagent.android.ui.controllers.StartupReadinessCoordinator
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
import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.PersistedInteractionMessage
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.PersistedToolCall
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.SessionPersistence
import com.pocketagent.android.ui.state.SessionStateLoadResult
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ModelResidencyPolicy
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.StreamUserMessageRequest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val runtimeFacade: RuntimeGateway,
    private val sessionPersistence: SessionPersistence,
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
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private var startupProbeJob: Job? = null
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

        val toolIntent = parseToolIntent(prompt)
        if (toolIntent == null && !isRuntimeReadyForSend(snapshot.runtime)) {
            val uiError = startupBlockError(snapshot.runtime)
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
        val performanceConfig = resolvePerformanceConfig(
            profile = snapshot.runtime.performanceProfile,
            gpuEnabled = snapshot.runtime.gpuAccelerationEnabled,
        )
        val requestTimeoutMs = resolveRequestTimeoutMs(performanceConfig)
        val userMessage = createMessage(
            role = MessageRole.USER,
            content = prompt,
            kind = if (toolIntent != null) MessageKind.TOOL else MessageKind.TEXT,
            toolName = toolIntent?.name,
            toolArgsJson = toolIntent?.jsonArgs,
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
                runtime = state.runtime
                    .copy(
                        modelRuntimeStatus = if (toolIntent == null) ModelRuntimeStatus.LOADING else state.runtime.modelRuntimeStatus,
                        modelStatusDetail = if (toolIntent == null) "Loading model..." else state.runtime.modelStatusDetail,
                        sendElapsedMs = if (toolIntent == null) 0L else state.runtime.sendElapsedMs,
                        sendSlowState = if (toolIntent == null) null else state.runtime.sendSlowState,
                    )
                    .clearError(),
            )
        }
        persistState()

        if (toolIntent != null) {
            executeToolIntent(sessionId = activeSession.id, toolIntent = toolIntent)
            return
        }

        val assistantMessageId = newMessageId(prefix = "assistant-stream")
        val requestId = newRequestId()
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
                            modelStatusDetail = readyStatusDetail(runtimeFacade.runtimeBackend()),
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
                request = StreamUserMessageRequest(
                    sessionId = SessionId(activeSession.id),
                    requestId = requestId,
                    userText = prompt,
                    taskType = resolveTaskType(prompt),
                    maxTokens = resolveMaxTokens(prompt, performanceConfig),
                    deviceState = DEFAULT_DEVICE_STATE,
                    requestTimeoutMs = requestTimeoutMs,
                    performanceConfig = performanceConfig,
                    residencyPolicy = ModelResidencyPolicy(
                        keepLoadedWhileAppForeground = true,
                        idleUnloadTtlMs = IDLE_MODEL_UNLOAD_TTL_MS,
                        warmupOnStartup = true,
                    ),
                ),
                requestTimeoutMs = requestTimeoutMs,
                streamReducer = streamReducer,
                sendStartedAtMs = sendStartedAtMs,
                onEvent = { event, nextState ->
                    when (event) {
                        is ChatStreamEvent.Started -> {
                            _uiState.update { state ->
                                state.copy(
                                    runtime = state.runtime.copy(
                                        modelStatusDetail = "Prefill...",
                                    ),
                                )
                            }
                        }

                        is ChatStreamEvent.TokenDelta -> {
                            pendingStreamingText = nextState.accumulatedText
                            flushPendingStreamingText(triggerToken = event.token)
                            _uiState.update { state ->
                                state.copy(
                                    runtime = state.runtime.copy(
                                        modelStatusDetail = "Generating...",
                                    ),
                                )
                            }
                        }

                        is ChatStreamEvent.Completed -> Unit
                        is ChatStreamEvent.Cancelled -> Unit
                        is ChatStreamEvent.Failed -> Unit
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
        if (!isRuntimeReady(snapshot.runtime)) {
            val uiError = startupBlockError(snapshot.runtime)
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
            kind = MessageKind.TOOL,
            toolName = toolName,
            toolArgsJson = jsonArgs,
        )
        updateActiveSession(activeSession.id) { session ->
            session.copy(
                messages = session.messages + request,
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
        runtimeFacade.setRoutingMode(mode)
        _uiState.update { state ->
            state.copy(runtime = state.runtime.copy(routingMode = mode))
        }
        persistState()
    }

    fun setPerformanceProfile(profile: RuntimePerformanceProfile) {
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
        _uiState.update { state ->
            val supported = state.runtime.gpuAccelerationSupported
            val effective = enabled && supported
            val detail = if (enabled && !supported) {
                "GPU acceleration is unavailable on this build/device. Using CPU."
            } else {
                performanceProfileStatusDetail(
                    profile = state.runtime.performanceProfile,
                    gpuEnabled = effective,
                    gpuSupported = supported,
                )
            }
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
                firstSessionStage = if (isRuntimeReadyForSend(state.runtime)) {
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
        val runtimeBackend = runtimeFacade.runtimeBackend()
        val persistedResult = persistenceCoordinator.loadStateResult()
        val persisted = when (persistedResult) {
            is SessionStateLoadResult.Success -> persistedResult.state
            is SessionStateLoadResult.RecoverableCorruption -> persistedResult.resetState
            is SessionStateLoadResult.FatalCorruption -> PersistedChatState()
        }
        val loadError = sessionStateLoadError(persistedResult)
        val shouldRunStartupProbe = loadError == null
        val restoredRoutingMode = RoutingMode.valueOf(persisted.routingMode)
        val restoredPerformanceProfile = RuntimePerformanceProfile.valueOf(persisted.performanceProfile)
        val restoredFirstSessionStage = FirstSessionStage.valueOf(persisted.firstSessionStage)
        val restoredAdvancedUnlocked = true
        val initialFirstSessionStage = when {
            !persisted.onboardingCompleted -> FirstSessionStage.ONBOARDING
            restoredFirstSessionStage == FirstSessionStage.ONBOARDING -> FirstSessionStage.GET_READY
            else -> restoredFirstSessionStage
        }
        val gpuSupported = runtimeFacade.supportsGpuOffload()
        val restoredGpuEnabled = persisted.gpuAccelerationEnabled && gpuSupported
        runtimeFacade.setRoutingMode(restoredRoutingMode)
        val bootstrapRuntimeState = if (loadError == null) {
            RuntimeUiState(
                routingMode = restoredRoutingMode,
                performanceProfile = restoredPerformanceProfile,
                gpuAccelerationEnabled = restoredGpuEnabled,
                gpuAccelerationSupported = gpuSupported,
                runtimeBackend = runtimeBackend,
                startupProbeState = StartupProbeState.RUNNING,
                modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                modelStatusDetail = "Warming model and running startup checks...",
            ).clearError()
        } else {
            RuntimeUiState(
                routingMode = restoredRoutingMode,
                performanceProfile = restoredPerformanceProfile,
                gpuAccelerationEnabled = restoredGpuEnabled,
                gpuAccelerationSupported = gpuSupported,
                runtimeBackend = runtimeBackend,
                startupProbeState = StartupProbeState.BLOCKED,
                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                modelStatusDetail = loadError.userMessage,
                startupChecks = listOf(loadError.technicalDetail ?: loadError.userMessage),
            ).withUiError(loadError)
        }

        val restoredSessions = persisted.sessions.map { session ->
            val turns = session.messages
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                .map { message ->
                    Turn(
                        role = if (message.role == MessageRole.USER) "user" else "assistant",
                        content = message.content,
                        timestampEpochMs = message.timestampEpochMs,
                    )
                }
            runtimeFacade.restoreSession(sessionId = SessionId(session.id), turns = turns)
            session
        }

        if (restoredSessions.isEmpty()) {
            val newSessionId = runtimeFacade.createSession().value
            val now = System.currentTimeMillis()
            _uiState.value = ChatUiState(
                sessions = listOf(
                    ChatSessionUiModel(
                        id = newSessionId,
                        title = "New chat",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        messages = emptyList(),
                    ),
                ),
                activeSessionId = newSessionId,
                runtime = bootstrapRuntimeState,
                showOnboarding = !persisted.onboardingCompleted,
                firstSessionStage = initialFirstSessionStage,
                advancedUnlocked = restoredAdvancedUnlocked,
                firstAnswerCompleted = persisted.firstAnswerCompleted,
                followUpCompleted = persisted.followUpCompleted,
                firstSessionTelemetryEvents = persisted.firstSessionTelemetryEvents,
            )
            ensureSimpleFirstEnteredTelemetryIfNeeded()
            persistState()
            if (shouldRunStartupProbe) {
                launchStartupProbe()
            }
            return
        }

        val activeSessionId = persisted.activeSessionId
            ?.takeIf { id -> restoredSessions.any { it.id == id } }
            ?: restoredSessions.last().id

        _uiState.value = ChatUiState(
            sessions = restoredSessions,
            activeSessionId = activeSessionId,
            runtime = bootstrapRuntimeState,
            showOnboarding = !persisted.onboardingCompleted,
            firstSessionStage = initialFirstSessionStage,
            advancedUnlocked = restoredAdvancedUnlocked,
            firstAnswerCompleted = persisted.firstAnswerCompleted,
            followUpCompleted = persisted.followUpCompleted,
            firstSessionTelemetryEvents = persisted.firstSessionTelemetryEvents,
        )
        ensureSimpleFirstEnteredTelemetryIfNeeded()
        if (shouldRunStartupProbe) {
            launchStartupProbe()
        }
    }

    private fun launchStartupProbe(statusDetailOverride: String? = null) {
        startupProbeJob?.cancel()
        startupProbeJob = viewModelScope.launch(ioDispatcher) {
            _uiState.update { state ->
                state.copy(
                    runtime = state.runtime.copy(
                        startupProbeState = StartupProbeState.RUNNING,
                        modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                        modelStatusDetail = "Warming model and running startup checks...",
                    ).clearError(),
                )
            }
            val startupChecks = if (!BuildConfig.NATIVE_RUNTIME_LIBRARY_PACKAGED) {
                listOf(MISSING_NATIVE_RUNTIME_BUILD_CHECK)
            } else {
                startupProbeController.runStartupChecks(
                    runtimeGateway = runtimeFacade,
                    ioDispatcher = ioDispatcher,
                    timeoutMs = runtimeStartupProbeTimeoutMs,
                )
            }
            val gpuSupported = runtimeFacade.supportsGpuOffload()
            val runtimeBackend = runtimeFacade.runtimeBackend()
            val readinessDecision = startupReadinessCoordinator.decide(
                startupChecks = startupChecks,
                runtimeBackend = runtimeBackend,
                statusDetailOverride = statusDetailOverride,
            )
            _uiState.update { state ->
                val nextRuntime = state.runtime.copy(
                    runtimeBackend = runtimeBackend,
                    gpuAccelerationSupported = gpuSupported,
                    gpuAccelerationEnabled = state.runtime.gpuAccelerationEnabled && gpuSupported,
                    startupProbeState = readinessDecision.startupProbeState,
                    modelRuntimeStatus = readinessDecision.modelRuntimeStatus,
                    modelStatusDetail = readinessDecision.modelStatusDetail,
                    startupChecks = startupChecks,
                    startupWarnings = readinessDecision.startupWarnings,
                ).withUiError(readinessDecision.startupError)
                val sendAllowed = readinessDecision.startupProbeState == StartupProbeState.READY
                val blocked = readinessDecision.startupProbeState == StartupProbeState.BLOCKED ||
                    readinessDecision.startupProbeState == StartupProbeState.BLOCKED_TIMEOUT
                val nextStage = when {
                    state.showOnboarding -> FirstSessionStage.ONBOARDING
                    state.firstAnswerCompleted -> state.firstSessionStage
                    sendAllowed -> FirstSessionStage.READY_TO_CHAT
                    blocked -> FirstSessionStage.GET_READY
                    else -> state.firstSessionStage
                }
                val completedGetReadyNow = state.firstSessionStage == FirstSessionStage.GET_READY &&
                    nextStage == FirstSessionStage.READY_TO_CHAT
                val telemetry = if (completedGetReadyNow) {
                    addTelemetryEventIfMissing(
                        events = state.firstSessionTelemetryEvents,
                        eventName = TELEMETRY_EVENT_GET_READY_COMPLETED,
                    )
                } else {
                    state.firstSessionTelemetryEvents
                }
                state.copy(
                    runtime = nextRuntime,
                    firstSessionStage = nextStage,
                    firstSessionTelemetryEvents = telemetry,
                )
            }
            persistState()
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
        val state = _uiState.value
        persistenceCoordinator.saveState(
            PersistedChatState(
                sessions = state.sessions.map { session ->
                    session.copy(
                        messages = session.messages.map { message -> message.copy(isStreaming = false) },
                    )
                },
                activeSessionId = state.activeSessionId,
                routingMode = state.runtime.routingMode.name,
                performanceProfile = state.runtime.performanceProfile.name,
                gpuAccelerationEnabled = state.runtime.gpuAccelerationEnabled,
                onboardingCompleted = !state.showOnboarding,
                firstSessionStage = state.firstSessionStage.name,
                advancedUnlocked = state.advancedUnlocked,
                firstAnswerCompleted = state.firstAnswerCompleted,
                followUpCompleted = state.followUpCompleted,
                firstSessionTelemetryEvents = state.firstSessionTelemetryEvents,
            ),
        )
    }

    private fun executeToolCommand(
        sessionId: String,
        toolName: String,
        jsonArgs: String,
    ) {
        viewModelScope.launch(ioDispatcher) {
            runCatching { sendController.runTool(toolName = toolName, jsonArgs = jsonArgs) }
                .onSuccess { toolOutput ->
                    val mappedError = UiErrorMapper.fromToolResult(toolOutput)
                    if (mappedError != null) {
                        appendSystemMessage(
                            sessionId = sessionId,
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
                    val response = createMessage(
                        role = MessageRole.ASSISTANT,
                        content = when (toolOutput) {
                            is com.pocketagent.runtime.ToolExecutionResult.Success -> toolOutput.content
                            is com.pocketagent.runtime.ToolExecutionResult.Failure -> {
                                toolOutput.failure.technicalDetail ?: toolOutput.failure.userMessage
                            }
                        },
                        kind = MessageKind.TOOL,
                        toolName = toolName,
                    )
                    updateActiveSession(sessionId) { session ->
                        session.copy(
                            messages = session.messages + response,
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
                .onFailure { error ->
                    val uiError = UiErrorMapper.runtimeFailure(error.message ?: "Tool request failed.")
                    appendSystemMessage(
                        sessionId = sessionId,
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

    private fun executeToolIntent(sessionId: String, toolIntent: ToolIntent) {
        executeToolCommand(
            sessionId = sessionId,
            toolName = toolIntent.name,
            jsonArgs = toolIntent.jsonArgs,
        )
    }

    private fun parseToolIntent(prompt: String): ToolIntent? {
        val normalized = prompt.trim()
        val lowercase = normalized.lowercase()
        val calcPrefix = listOf("calculate ", "calc ", "what is ")
            .firstOrNull { lowercase.startsWith(it) }
        if (calcPrefix != null) {
            val expression = normalized.drop(calcPrefix.length).trim()
            if (expression.matches(Regex("[0-9+\\-*/().\\s]{1,64}")) && expression.any { it.isDigit() }) {
                return ToolIntent(name = "calculator", jsonArgs = """{"expression":"$expression"}""")
            }
        }
        if (lowercase == "time" || lowercase == "date" || lowercase.contains("what time")) {
            return ToolIntent(name = "date_time", jsonArgs = "{}")
        }
        if (lowercase.startsWith("search ")) {
            val query = normalized.drop("search ".length).trim()
            if (query.isNotEmpty()) {
                return ToolIntent(name = "local_search", jsonArgs = """{"query":"${escapeJson(query)}"}""")
            }
        }
        if (lowercase.startsWith("find notes ")) {
            val query = normalized.drop("find notes ".length).trim()
            if (query.isNotEmpty()) {
                return ToolIntent(name = "notes_lookup", jsonArgs = """{"query":"${escapeJson(query)}"}""")
            }
        }
        if (lowercase.startsWith("remind me to ")) {
            val title = normalized.drop("remind me to ".length).trim()
            if (title.isNotEmpty()) {
                return ToolIntent(name = "reminder_create", jsonArgs = """{"title":"${escapeJson(title)}"}""")
            }
        }
        return null
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun resolveTaskType(prompt: String): String {
        return if (prompt.length >= LONG_PROMPT_LENGTH) "long_text" else "short_text"
    }

    private fun resolveMaxTokens(prompt: String, performanceConfig: PerformanceRuntimeConfig): Int {
        val promptBudget = if (prompt.length >= LONG_PROMPT_LENGTH) {
            LONG_PROMPT_MAX_TOKENS
        } else {
            SHORT_PROMPT_MAX_TOKENS
        }
        return minOf(promptBudget, performanceConfig.maxTokensDefault.coerceAtLeast(16))
    }

    private fun isRuntimeReady(runtime: RuntimeUiState): Boolean {
        return runtime.startupProbeState == StartupProbeState.READY &&
            runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
    }

    private fun isRuntimeReadyForSend(runtime: RuntimeUiState): Boolean {
        return isRuntimeReady(runtime)
    }

    private fun resolveRequestTimeoutMs(performanceConfig: PerformanceRuntimeConfig): Long {
        if (runtimeGenerationTimeoutMs > 0L) {
            return runtimeGenerationTimeoutMs
        }
        return performanceConfig.requestTimeoutMs
    }

    private fun resolvePerformanceConfig(
        profile: RuntimePerformanceProfile,
        gpuEnabled: Boolean,
    ): PerformanceRuntimeConfig {
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return PerformanceRuntimeConfig.forProfile(
            profile = profile,
            availableCpuThreads = cpuThreads,
            gpuEnabled = gpuEnabled,
        )
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

    private fun startupBlockError(runtime: RuntimeUiState): UiError {
        val checks = runtime.startupChecks.ifEmpty {
            listOf(runtime.modelStatusDetail ?: "Runtime startup checks are still running.")
        }
        return UiErrorMapper.startupFailure(checks)
            ?: UiErrorMapper.runtimeFailure(runtime.modelStatusDetail ?: "Runtime is not ready yet.")
    }

    private fun sessionStateLoadError(loadResult: SessionStateLoadResult): UiError? {
        return when (loadResult) {
            is SessionStateLoadResult.Success -> null
            is SessionStateLoadResult.RecoverableCorruption -> UiError(
                code = UI_SESSION_STATE_CORRUPTION_CODE,
                userMessage = "Saved chat state was corrupted and reset. Refresh runtime checks to continue.",
                technicalDetail = loadResult.technicalDetail,
            )
            is SessionStateLoadResult.FatalCorruption -> UiError(
                code = UI_SESSION_STATE_FATAL_CODE,
                userMessage = "Saved chat state could not be loaded. Refresh runtime checks and retry.",
                technicalDetail = loadResult.technicalDetail,
            )
        }
    }

    private fun readyStatusDetail(runtimeBackend: String?): String {
        return if (runtimeBackend.isNullOrBlank()) {
            "Runtime model ready"
        } else {
            "Runtime model ready ($runtimeBackend)"
        }
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
        requestId: String? = null,
        finishReason: String? = null,
        terminalEventSeen: Boolean = false,
    ): MessageUiModel {
        val interactionToolCall = if (
            role == MessageRole.USER &&
            kind == MessageKind.TOOL &&
            !toolName.isNullOrBlank() &&
            !toolArgsJson.isNullOrBlank()
        ) {
            listOf(
                PersistedToolCall(
                    id = "toolcall-${UUID.randomUUID()}",
                    name = toolName,
                    argumentsJson = toolArgsJson,
                ),
            )
        } else {
            emptyList()
        }
        val interaction = PersistedInteractionMessage(
            role = role.name,
            parts = listOf(PersistedInteractionPart(type = "text", text = content)),
            toolCalls = interactionToolCall,
            metadata = buildMap {
                put("kind", kind.name)
                imagePath?.let { put("imagePath", it) }
                toolName?.let { put("toolName", it) }
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

    private fun newMessageId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    companion object {
        private const val UI_SESSION_STATE_CORRUPTION_CODE = "UI-SESSION-001"
        private const val UI_SESSION_STATE_FATAL_CODE = "UI-SESSION-002"
        private const val MISSING_NATIVE_RUNTIME_BUILD_CHECK =
            "Build is missing native runtime library (libpocket_llama.so). " +
                "Install an app build that packages native runtime."
        private const val TITLE_MAX_CHARS = 42
        private const val LONG_PROMPT_LENGTH = 160
        private const val SHORT_PROMPT_MAX_TOKENS = 32
        private const val LONG_PROMPT_MAX_TOKENS = 96
        private const val ONBOARDING_LAST_PAGE = 2
        private const val DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS = 30_000L
        private const val IDLE_MODEL_UNLOAD_TTL_MS = 10 * 60 * 1000L
        private const val STREAM_UI_UPDATE_MIN_INTERVAL_MS = 80L
        private const val TELEMETRY_EVENT_SIMPLE_FIRST_ENTERED = "simple_first_entered"
        private const val TELEMETRY_EVENT_GET_READY_STARTED = "get_ready_started"
        private const val TELEMETRY_EVENT_GET_READY_COMPLETED = "get_ready_completed"
        private const val TELEMETRY_EVENT_FIRST_ANSWER_COMPLETED = "first_answer_completed"
        private const val TELEMETRY_EVENT_FOLLOW_UP_COMPLETED = "follow_up_completed"
        private const val TELEMETRY_EVENT_ADVANCED_UNLOCKED = "advanced_unlocked"
        private val DEFAULT_DEVICE_STATE = DeviceState(
            batteryPercent = 85,
            thermalLevel = 3,
            ramClassGb = 8,
        )
    }
}

private fun addTelemetryEventIfMissing(
    events: List<FirstSessionTelemetryEvent>,
    eventName: String,
): List<FirstSessionTelemetryEvent> {
    if (events.any { it.eventName == eventName }) {
        return events
    }
    return (events + FirstSessionTelemetryEvent(eventName = eventName, eventTimeUtc = Instant.now().toString()))
        .takeLast(64)
}

private data class ToolIntent(
    val name: String,
    val jsonArgs: String,
)

private fun RuntimeUiState.clearError(): RuntimeUiState {
    return copy(
        lastErrorCode = null,
        lastErrorUserMessage = null,
        lastErrorTechnicalDetail = null,
        lastError = null,
    )
}

private fun RuntimeUiState.withUiError(error: UiError?): RuntimeUiState {
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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(runtimeFacade, sessionPersistence) as T
        }
        throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
    }
}
