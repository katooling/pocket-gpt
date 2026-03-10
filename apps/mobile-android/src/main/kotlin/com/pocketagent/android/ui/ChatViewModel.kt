package com.pocketagent.android.ui

import android.util.Log
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeResult
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.runtime.RuntimeGateway
import com.pocketagent.android.runtime.RuntimeTuning
import com.pocketagent.android.ui.controllers.ChatPersistenceFlow
import com.pocketagent.android.ui.controllers.ChatStreamCoordinator
import com.pocketagent.android.ui.controllers.ChatPersistenceCoordinator
import com.pocketagent.android.ui.controllers.ChatPersistenceQueue
import com.pocketagent.android.ui.controllers.DeviceStateProvider
import com.pocketagent.android.ui.controllers.ChatSendFlow
import com.pocketagent.android.ui.controllers.ChatSendController
import com.pocketagent.android.ui.controllers.PersistenceQueueMetrics
import com.pocketagent.android.ui.controllers.ChatStartupProbeOrchestrator
import com.pocketagent.android.ui.controllers.SendReducer
import com.pocketagent.android.ui.controllers.ChatStartupFlow
import com.pocketagent.android.ui.controllers.StartupProbeController
import com.pocketagent.android.ui.controllers.StartupReadinessCoordinator
import com.pocketagent.android.ui.controllers.TimelineProjector
import com.pocketagent.android.ui.controllers.ToolLoopUseCase
import androidx.lifecycle.ViewModel
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
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.SessionPersistence
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.runtime.RuntimePerformanceProfile
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
    internal val runtimeFacade: RuntimeGateway,
    sessionPersistence: SessionPersistence,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runtimeGenerationTimeoutMs: Long = 0L,
    private val runtimeStartupProbeTimeoutMs: Long = DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS,
    private val sendController: ChatSendController = ChatSendController(runtimeFacade, ioDispatcher),
    internal val streamCoordinator: ChatStreamCoordinator = ChatStreamCoordinator(),
    private val startupProbeController: StartupProbeController = StartupProbeController(),
    private val startupReadinessCoordinator: StartupReadinessCoordinator = StartupReadinessCoordinator(
        runtimeProfile = resolveModelRuntimeProfile(isDebugBuild = BuildConfig.DEBUG),
    ),
    private val persistenceCoordinator: ChatPersistenceCoordinator = ChatPersistenceCoordinator(sessionPersistence),
    internal val deviceStateProvider: DeviceStateProvider = DeviceStateProvider.DEFAULT,
    internal val runtimeTuning: RuntimeTuning = RuntimeTuning.DISABLED,
    internal val timelineProjector: TimelineProjector = TimelineProjector(),
    internal val persistenceFlow: ChatPersistenceFlow = ChatPersistenceFlow(persistenceCoordinator),
    internal val startupFlow: ChatStartupFlow = ChatStartupFlow(
        runtimeGateway = runtimeFacade,
        startupProbeController = startupProbeController,
        startupReadinessCoordinator = startupReadinessCoordinator,
        ioDispatcher = ioDispatcher,
        runtimeStartupProbeTimeoutMs = runtimeStartupProbeTimeoutMs,
        nativeRuntimeLibraryPackaged = BuildConfig.NATIVE_RUNTIME_LIBRARY_PACKAGED,
        timelineProjector = timelineProjector,
    ),
    internal val sendFlow: ChatSendFlow = ChatSendFlow(
        runtimeGenerationTimeoutMs = runtimeGenerationTimeoutMs,
        deviceStateProvider = deviceStateProvider,
        runtimeTuning = runtimeTuning,
    ),
    internal val sendReducer: SendReducer = SendReducer(),
    internal val toolLoopUseCase: ToolLoopUseCase = ToolLoopUseCase(sendController),
) : ViewModel() {
    internal val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private var gpuProbeRefreshJob: Job? = null
    @Volatile
    internal var activeSendRequestId: String? = null
    @Volatile
    private var lastKeepAliveTouchAtMs: Long = 0L
    internal val persistenceQueue = ChatPersistenceQueue(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        toPersistedState = { state -> persistenceFlow.toPersistedState(state) },
        savePersistedState = { persisted -> persistenceFlow.savePersistedState(persisted) },
        debounceMs = CHAT_PERSIST_DEBOUNCE_MS,
        onMetrics = { metrics ->
            val shouldLog = metrics.writeCount <= 3 || metrics.writeCount % 8 == 0
            if (shouldLog) {
                runCatching {
                    Log.i(
                        LOG_TAG,
                        "CHAT_PERSIST|writes=${metrics.writeCount}|last_ms=${metrics.lastPersistDurationMs}|" +
                            "median_ms=${metrics.medianPersistDurationMs}|last_bytes=${metrics.lastPayloadBytes}|" +
                            "median_bytes=${metrics.medianPayloadBytes}",
                    )
                }
            }
        },
    )
    private val startupProbeOrchestrator = ChatStartupProbeOrchestrator(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        runtimeGateway = runtimeFacade,
        startupFlow = startupFlow,
        startupReadinessCoordinator = startupReadinessCoordinator,
        updateState = { transform -> _uiState.update(transform) },
        onPersist = { persistState() },
        onProbeApplied = { refreshGpuProbeStatusIfPending() },
        log = { phase, probeToken, detail, error ->
            logStartupProbe(
                phase = phase,
                probeToken = probeToken,
                statusDetailOverride = detail,
                error = error,
            )
        },
    )

    init {
        bootstrapState()
    }

    fun onComposerChanged(text: String) {
        _uiState.update { state ->
            state.copy(composer = state.composer.copy(text = text))
        }
        if (text.isBlank()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastKeepAliveTouchAtMs >= COMPOSER_KEEP_ALIVE_TOUCH_DEBOUNCE_MS) {
            lastKeepAliveTouchAtMs = now
            runtimeFacade.touchKeepAlive()
        }
    }

    fun sendMessage() {
        sendMessageInternal()
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

    fun setKeepAlivePreference(preference: RuntimeKeepAlivePreference) {
        if (_uiState.value.runtime.keepAlivePreference == preference) {
            return
        }
        _uiState.update { state ->
            state.copy(runtime = state.runtime.copy(keepAlivePreference = preference))
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
        startupProbeOrchestrator.launch(statusDetailOverride = statusDetailOverride)
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
            while (isActive) {
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

    internal fun updateRuntimeGpuProbeState(probe: GpuProbeResult): Boolean {
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

    internal fun updateStreamingMessage(sessionId: String, messageId: String, text: String) {
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

    internal fun finalizeStreamingMessage(
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

    internal fun appendSystemMessage(
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

    internal fun messageContent(
        sessionId: String,
        messageId: String,
    ): String? {
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return null
        return session.messages.firstOrNull { it.id == messageId }?.content
    }

    internal fun updateActiveSession(
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

    internal fun persistState() {
        persistenceQueue.enqueue(_uiState.value)
    }

    internal fun persistenceMetricsSnapshot(): PersistenceQueueMetrics {
        return persistenceQueue.metricsSnapshot()
    }

    override fun onCleared() {
        startupProbeOrchestrator.cancel()
        gpuProbeRefreshJob?.cancel()
        persistenceQueue.close()
        super.onCleared()
    }

    private fun executeToolCommand(
        sessionId: String,
        toolName: String,
        jsonArgs: String,
        toolCallId: String,
    ) {
        executeToolCommandInternal(
            sessionId = sessionId,
            toolName = toolName,
            jsonArgs = jsonArgs,
            toolCallId = toolCallId,
        )
    }

    internal fun updateToolCallStatus(
        sessionId: String,
        toolCallId: String,
        status: PersistedToolCallStatus,
    ) {
        updateToolCallStatusInternal(
            sessionId = sessionId,
            toolCallId = toolCallId,
            status = status,
        )
    }

    internal fun createMessage(
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
        return createMessageInternal(
            role = role,
            content = content,
            kind = kind,
            imagePath = imagePath,
            toolName = toolName,
            toolArgsJson = toolArgsJson,
            toolCallId = toolCallId,
            toolCallStatus = toolCallStatus,
            requestId = requestId,
            finishReason = finishReason,
            terminalEventSeen = terminalEventSeen,
        )
    }

    internal fun maybeAdvanceAfterAssistantResponse() {
        maybeAdvanceAfterAssistantResponseInternal()
    }

    internal fun ensureSimpleFirstEnteredTelemetryIfNeeded() {
        ensureSimpleFirstEnteredTelemetryIfNeededInternal()
    }

    internal fun recordFirstSessionEventOnce(eventName: String) {
        recordFirstSessionEventOnceInternal(eventName)
    }

}
