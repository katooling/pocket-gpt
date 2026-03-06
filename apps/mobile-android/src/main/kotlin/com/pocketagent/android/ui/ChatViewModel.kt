package com.pocketagent.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ComposerUiState
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
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.StreamReducerState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.ModelResidencyPolicy
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.RuntimeGenerationTimeoutException
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.StreamUserMessageRequest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

class ChatViewModel(
    private val runtimeFacade: MvpRuntimeFacade,
    private val sessionPersistence: SessionPersistence,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runtimeGenerationTimeoutMs: Long = 0L,
    private val runtimeStartupProbeTimeoutMs: Long = DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS,
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
            val streamReducerLock = Any()
            var streamState = StreamReducerState.initial(requestId = requestId)

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

            fun reduce(block: (StreamReducerState) -> StreamReducerState): Pair<StreamTerminalState?, StreamReducerState> {
                synchronized(streamReducerLock) {
                    val previous = streamState.terminal
                    streamState = block(streamState)
                    return previous to streamState
                }
            }

            fun hasTerminal(): Boolean = synchronized(streamReducerLock) { streamState.terminal != null }

            fun streamFirstTokenMs(): Long? = synchronized(streamReducerLock) { streamState.firstTokenMs }

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
            }

            val elapsedTicker = launch {
                while (!hasTerminal()) {
                    val elapsed = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                    val slowState = when {
                        streamFirstTokenMs() != null -> null
                        elapsed >= NO_FIRST_TOKEN_STALL_MS -> "Still working on this device. You can keep waiting, or tap Cancel to stop."
                        elapsed >= NO_FIRST_TOKEN_WARN_MS -> "Loading model and prefill can take longer on older phones. You can keep waiting or cancel."
                        else -> null
                    }
                    _uiState.update { state ->
                        state.copy(
                            runtime = state.runtime.copy(
                                sendElapsedMs = elapsed,
                                sendSlowState = slowState,
                            ),
                        )
                    }
                    delay(SEND_ELAPSED_UPDATE_INTERVAL_MS)
                }
            }

            val terminalWatchdog = launch {
                delay(requestTimeoutMs + SEND_TERMINAL_WATCHDOG_GRACE_MS)
                val (previousTerminal, nextState) = reduce { state ->
                    streamReducer.onWatchdogTimeout(state)
                }
                if (previousTerminal != null || nextState.terminal == null) return@launch
                elapsedTicker.cancel()
                flushPendingStreamingText(force = true)
                runtimeFacade.cancelGenerationByRequest(requestId)
                finalizeFromTerminal(nextState.terminal!!)
            }

            runCatching {
                withTimeout(requestTimeoutMs) {
                    runtimeFacade.streamUserMessage(
                        StreamUserMessageRequest(
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
                    ).collect { event ->
                        if (event.requestId != requestId || hasTerminal()) {
                            return@collect
                        }
                        val elapsed = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                        val (previousTerminal, nextState) = reduce { state ->
                            streamReducer.onEvent(state = state, event = event, elapsedMs = elapsed)
                        }
                        if (previousTerminal != null) return@collect
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

                            is ChatStreamEvent.Completed -> {
                                // Terminal transition is handled below by reducer-derived state.
                            }

                            is ChatStreamEvent.Cancelled -> {
                                // Terminal transition is handled below by reducer-derived state.
                            }

                            is ChatStreamEvent.Failed -> {
                                // Terminal transition is handled below by reducer-derived state.
                            }
                        }
                        nextState.terminal?.let { terminal ->
                            terminalWatchdog.cancel()
                            elapsedTicker.cancel()
                            flushPendingStreamingText(force = true)
                            finalizeFromTerminal(terminal)
                        }
                    }
                }
            }.onFailure { error ->
                val (previousTerminal, nextState) = reduce { state ->
                    streamReducer.onFailure(state = state, error = error)
                }
                if (previousTerminal != null || nextState.terminal == null) return@onFailure
                terminalWatchdog.cancel()
                elapsedTicker.cancel()
                flushPendingStreamingText(force = true)
                val generationTimedOut = error is TimeoutCancellationException || error is RuntimeGenerationTimeoutException
                if (generationTimedOut) {
                    runtimeFacade.cancelGenerationByRequest(requestId)
                }
                finalizeFromTerminal(nextState.terminal!!)
            }
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
                runtimeFacade.analyzeImage(imagePath = imagePath, prompt = "Describe this image.")
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
                val assistant = createMessage(
                    role = MessageRole.ASSISTANT,
                    content = result,
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

        viewModelScope.launch(ioDispatcher) {
            runCatching { runtimeFacade.runTool(toolName = toolName, jsonArgs = jsonArgs) }
                .onSuccess { toolOutput ->
                    val mappedError = UiErrorMapper.fromToolResult(toolOutput)
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
                    val response = createMessage(
                        role = MessageRole.ASSISTANT,
                        content = toolOutput,
                        kind = MessageKind.TOOL,
                        toolName = toolName,
                    )
                    updateActiveSession(activeSession.id) { session ->
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
        _uiState.update { it.copy(isAdvancedSheetOpen = isOpen) }
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
            )
        }
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

    private fun bootstrapState() {
        val runtimeBackend = runtimeFacade.runtimeBackend()
        val persisted = sessionPersistence.loadState()
        val restoredRoutingMode = runCatching { RoutingMode.valueOf(persisted.routingMode) }
            .getOrDefault(runtimeFacade.getRoutingMode())
        val restoredPerformanceProfile = runCatching { RuntimePerformanceProfile.valueOf(persisted.performanceProfile) }
            .getOrDefault(RuntimePerformanceProfile.BALANCED)
        val gpuSupported = runtimeFacade.supportsGpuOffload()
        val restoredGpuEnabled = persisted.gpuAccelerationEnabled && gpuSupported
        runtimeFacade.setRoutingMode(restoredRoutingMode)

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
                runtime = RuntimeUiState(
                    routingMode = restoredRoutingMode,
                    performanceProfile = restoredPerformanceProfile,
                    gpuAccelerationEnabled = restoredGpuEnabled,
                    gpuAccelerationSupported = gpuSupported,
                    runtimeBackend = runtimeBackend,
                    startupProbeState = StartupProbeState.RUNNING,
                    modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                    modelStatusDetail = "Warming model and running startup checks...",
                ).clearError(),
                showOnboarding = !persisted.onboardingCompleted,
            )
            persistState()
            launchStartupProbe()
            return
        }

        val activeSessionId = persisted.activeSessionId
            ?.takeIf { id -> restoredSessions.any { it.id == id } }
            ?: restoredSessions.last().id

        _uiState.value = ChatUiState(
            sessions = restoredSessions,
            activeSessionId = activeSessionId,
            runtime = RuntimeUiState(
                routingMode = restoredRoutingMode,
                performanceProfile = restoredPerformanceProfile,
                gpuAccelerationEnabled = restoredGpuEnabled,
                gpuAccelerationSupported = gpuSupported,
                runtimeBackend = runtimeBackend,
                startupProbeState = StartupProbeState.RUNNING,
                modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                modelStatusDetail = "Warming model and running startup checks...",
            ).clearError(),
            showOnboarding = !persisted.onboardingCompleted,
        )
        launchStartupProbe()
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
            val startupChecks = try {
                withTimeout(runtimeStartupProbeTimeoutMs) {
                    runInterruptible(ioDispatcher) { runtimeFacade.runStartupChecks() }
                }
            } catch (_: TimeoutCancellationException) {
                val timeoutSeconds = (runtimeStartupProbeTimeoutMs / 1000L).coerceAtLeast(1L)
                listOf("Startup checks timed out after ${timeoutSeconds}s.")
            }
            val timeoutOnlyFailure = isStartupTimeoutOnlyFailure(startupChecks)
            val optionalModelOnlyFailure = isOptionalModelOnlyStartupFailure(startupChecks)
            val gpuSupported = runtimeFacade.supportsGpuOffload()
            val startupError = if (timeoutOnlyFailure || optionalModelOnlyFailure) {
                null
            } else {
                UiErrorMapper.startupFailure(startupChecks)
            }
            val startupModelStatus = when {
                startupChecks.isEmpty() -> ModelRuntimeStatus.READY
                timeoutOnlyFailure -> ModelRuntimeStatus.LOADING
                optionalModelOnlyFailure -> ModelRuntimeStatus.READY
                else -> resolveModelStatusFromStartupChecks(startupChecks)
            }
            val runtimeBackend = runtimeFacade.runtimeBackend()
            val nextProbeState = when {
                startupChecks.isEmpty() -> StartupProbeState.READY
                timeoutOnlyFailure || optionalModelOnlyFailure -> StartupProbeState.DEGRADED
                else -> StartupProbeState.BLOCKED
            }
            val startupWarnings = if (timeoutOnlyFailure || optionalModelOnlyFailure) {
                startupChecks
            } else {
                emptyList()
            }
            _uiState.update { state ->
                state.copy(
                    runtime = state.runtime.copy(
                        runtimeBackend = runtimeBackend,
                        gpuAccelerationSupported = gpuSupported,
                        gpuAccelerationEnabled = state.runtime.gpuAccelerationEnabled && gpuSupported,
                        startupProbeState = nextProbeState,
                        modelRuntimeStatus = startupModelStatus,
                        modelStatusDetail = if (startupChecks.isEmpty()) {
                            statusDetailOverride ?: readyStatusDetail(runtimeBackend)
                        } else if (timeoutOnlyFailure) {
                            "Startup checks exceeded the probe window. Chat is still available; first output may take longer on older devices."
                        } else if (optionalModelOnlyFailure) {
                            optionalModelStatusDetail(startupChecks)
                        } else {
                            startupChecks.firstOrNull() ?: "Runtime startup checks failed."
                        },
                        startupChecks = startupChecks,
                        startupWarnings = startupWarnings,
                    ).withUiError(startupError),
                )
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
        val state = _uiState.value
        sessionPersistence.saveState(
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
            ),
        )
    }

    private fun executeToolIntent(sessionId: String, toolIntent: ToolIntent) {
        viewModelScope.launch(ioDispatcher) {
            runCatching { runtimeFacade.runTool(toolName = toolIntent.name, jsonArgs = toolIntent.jsonArgs) }
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
                        content = toolOutput,
                        kind = MessageKind.TOOL,
                        toolName = toolIntent.name,
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

    private fun resolveModelStatusFromStartupChecks(startupChecks: List<String>): ModelRuntimeStatus {
        if (startupChecks.isEmpty()) {
            return ModelRuntimeStatus.READY
        }
        val startupSummary = startupChecks.joinToString(" ").lowercase()
        if (startupSummary.contains("optional runtime model unavailable")) {
            return ModelRuntimeStatus.READY
        }
        return if (
            startupSummary.contains("missing runtime model") ||
            startupSummary.contains("artifact verification failed")
        ) {
            ModelRuntimeStatus.NOT_READY
        } else {
            ModelRuntimeStatus.ERROR
        }
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
        return isRuntimeReady(runtime) || runtime.startupProbeState == StartupProbeState.DEGRADED
    }

    private fun isStartupTimeoutOnlyFailure(startupChecks: List<String>): Boolean {
        if (startupChecks.isEmpty()) {
            return false
        }
        return startupChecks.all { check ->
            val normalized = check.lowercase()
            normalized.contains("startup checks timed out") || normalized.contains("timed out")
        }
    }

    private fun isOptionalModelOnlyStartupFailure(startupChecks: List<String>): Boolean {
        if (startupChecks.isEmpty()) {
            return false
        }
        return startupChecks.all { check ->
            check.lowercase().contains("optional runtime model unavailable")
        }
    }

    private fun optionalModelStatusDetail(startupChecks: List<String>): String {
        val missing = startupChecks
            .filter { it.lowercase().contains("optional runtime model unavailable") }
            .flatMap { check ->
                check.substringAfter(":", missingDelimiterValue = "")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            .toSet()
        val readyCount = (BASELINE_RUNTIME_MODELS.size - missing.size).coerceAtLeast(1)
        return if (missing.isEmpty()) {
            "Runtime ready. Optional models are still being provisioned."
        } else {
            "$readyCount model ready, ${missing.size} optional model unavailable (${missing.joinToString(", ")})."
        }
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

    private fun newRequestId(): String = "req-${UUID.randomUUID()}"

    private fun newMessageId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    companion object {
        private const val TITLE_MAX_CHARS = 42
        private const val LONG_PROMPT_LENGTH = 160
        private const val SHORT_PROMPT_MAX_TOKENS = 32
        private const val LONG_PROMPT_MAX_TOKENS = 96
        private const val ONBOARDING_LAST_PAGE = 2
        private const val DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS = 30_000L
        private const val IDLE_MODEL_UNLOAD_TTL_MS = 10 * 60 * 1000L
        private const val STREAM_UI_UPDATE_MIN_INTERVAL_MS = 80L
        private const val SEND_TERMINAL_WATCHDOG_GRACE_MS = 1_500L
        private const val SEND_ELAPSED_UPDATE_INTERVAL_MS = 1_000L
        private const val NO_FIRST_TOKEN_WARN_MS = 90_000L
        private const val NO_FIRST_TOKEN_STALL_MS = 300_000L
        private val BASELINE_RUNTIME_MODELS = setOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.QWEN_3_5_2B_Q4,
        )
        private val DEFAULT_DEVICE_STATE = DeviceState(
            batteryPercent = 85,
            thermalLevel = 3,
            ramClassGb = 8,
        )
    }
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
    private val runtimeFacade: MvpRuntimeFacade,
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
