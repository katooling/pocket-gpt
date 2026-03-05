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
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.SessionPersistence
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeGenerationTimeoutException
import com.pocketagent.runtime.StreamUserMessageRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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
    private val runtimeGenerationTimeoutMs: Long = DEFAULT_RUNTIME_GENERATION_TIMEOUT_MS,
    private val runtimeStartupProbeTimeoutMs: Long = DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private var startupProbeJob: Job? = null

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
        if (toolIntent == null && !isRuntimeReady(snapshot.runtime)) {
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
                runtime = state.runtime
                    .copy(
                        modelRuntimeStatus = if (toolIntent == null) ModelRuntimeStatus.LOADING else state.runtime.modelRuntimeStatus,
                        modelStatusDetail = if (toolIntent == null) "Loading runtime model..." else state.runtime.modelStatusDetail,
                    )
                    .clearError(),
            )
        }
        persistState()

        if (toolIntent != null) {
            executeToolIntent(sessionId = activeSession.id, toolIntent = toolIntent)
            return
        }

        val assistantMessageId = "assistant-stream-${System.currentTimeMillis()}"
        val requestId = "req-${System.currentTimeMillis()}-${(1000..9999).random()}"
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
            var firstTokenMs: Long? = null
            val sendStartedAtMs = System.currentTimeMillis()

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

            runCatching {
                withTimeout(runtimeGenerationTimeoutMs) {
                    runtimeFacade.streamUserMessage(
                        StreamUserMessageRequest(
                            sessionId = SessionId(activeSession.id),
                            requestId = requestId,
                            userText = prompt,
                            taskType = resolveTaskType(prompt),
                            maxTokens = resolveMaxTokens(prompt),
                            deviceState = DEFAULT_DEVICE_STATE,
                            requestTimeoutMs = runtimeGenerationTimeoutMs,
                        ),
                    ).collect { event ->
                        if (event.requestId != requestId) {
                            return@collect
                        }
                        when (event) {
                            is ChatStreamEvent.Started -> {
                                // request lifecycle started; keep UI in loading until first delta/terminal event.
                            }

                            is ChatStreamEvent.TokenDelta -> {
                                if (firstTokenMs == null && event.accumulatedText.isNotBlank()) {
                                    firstTokenMs = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                                }
                                pendingStreamingText = event.accumulatedText
                                flushPendingStreamingText(triggerToken = event.token)
                            }

                            is ChatStreamEvent.Completed -> {
                                flushPendingStreamingText(force = true)
                                finalizeStreamingMessage(
                                    sessionId = activeSession.id,
                                    messageId = assistantMessageId,
                                    finalText = event.response.text,
                                    requestId = event.requestId,
                                    finishReason = event.finishReason,
                                    terminalEventSeen = event.terminalEventSeen,
                                )
                                val effectiveFirstToken = firstTokenMs ?: event.firstTokenMs
                                val effectiveCompletion = event.completionMs ?: (System.currentTimeMillis() - sendStartedAtMs)
                                _uiState.update { state ->
                                    state.copy(
                                        composer = state.composer.copy(isSending = false),
                                        runtime = state.runtime.copy(
                                            runtimeBackend = runtimeFacade.runtimeBackend(),
                                            startupProbeState = StartupProbeState.READY,
                                            modelRuntimeStatus = ModelRuntimeStatus.READY,
                                            modelStatusDetail = readyStatusDetail(runtimeFacade.runtimeBackend()),
                                            activeModelId = event.response.modelId,
                                            lastFirstTokenLatencyMs = effectiveFirstToken,
                                            lastTotalLatencyMs = effectiveCompletion,
                                        ).clearError(),
                                    )
                                }
                                persistState()
                            }

                            is ChatStreamEvent.Cancelled -> {
                                flushPendingStreamingText(force = true)
                                runtimeFacade.cancelGenerationByRequest(requestId)
                                val uiError = UiErrorMapper.runtimeTimeout(runtimeGenerationTimeoutMs)
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
                                        requestId = event.requestId,
                                        finishReason = "cancelled",
                                        terminalEventSeen = event.terminalEventSeen,
                                    )
                                    appendSystemMessage(
                                        sessionId = activeSession.id,
                                        content = formatUserFacingError(uiError),
                                    )
                                } else {
                                    finalizeStreamingMessage(
                                        sessionId = activeSession.id,
                                        messageId = assistantMessageId,
                                        finalText = formatUserFacingError(uiError),
                                        role = MessageRole.SYSTEM,
                                        requestId = event.requestId,
                                        finishReason = "cancelled",
                                        terminalEventSeen = event.terminalEventSeen,
                                    )
                                }
                                _uiState.update { state ->
                                    state.copy(
                                        composer = state.composer.copy(isSending = false),
                                        runtime = state.runtime
                                            .copy(
                                                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                                                modelStatusDetail = uiError.userMessage,
                                            )
                                            .withUiError(uiError),
                                    )
                                }
                                persistState()
                            }

                            is ChatStreamEvent.Failed -> {
                                flushPendingStreamingText(force = true)
                                val uiError = UiErrorMapper.runtimeFailure(event.message)
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
                                        requestId = event.requestId,
                                        finishReason = "failed:${event.errorCode}",
                                        terminalEventSeen = event.terminalEventSeen,
                                    )
                                    appendSystemMessage(
                                        sessionId = activeSession.id,
                                        content = formatUserFacingError(uiError),
                                    )
                                } else {
                                    finalizeStreamingMessage(
                                        sessionId = activeSession.id,
                                        messageId = assistantMessageId,
                                        finalText = formatUserFacingError(uiError),
                                        role = MessageRole.SYSTEM,
                                        requestId = event.requestId,
                                        finishReason = "failed:${event.errorCode}",
                                        terminalEventSeen = event.terminalEventSeen,
                                    )
                                }
                                _uiState.update { state ->
                                    state.copy(
                                        composer = state.composer.copy(isSending = false),
                                        runtime = state.runtime
                                            .copy(
                                                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                                                modelStatusDetail = uiError.userMessage,
                                            )
                                            .withUiError(uiError),
                                    )
                                }
                                persistState()
                            }
                        }
                    }
                }
            }.onFailure { error ->
                flushPendingStreamingText(force = true)
                val generationTimedOut = error is TimeoutCancellationException || error is RuntimeGenerationTimeoutException
                if (generationTimedOut) {
                    runtimeFacade.cancelGenerationByRequest(requestId)
                }
                val uiError = if (generationTimedOut) {
                    UiErrorMapper.runtimeTimeout(runtimeGenerationTimeoutMs)
                } else {
                    UiErrorMapper.runtimeFailure(error.message)
                }
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
                        requestId = requestId,
                        finishReason = "timeout",
                        terminalEventSeen = true,
                    )
                    appendSystemMessage(
                        sessionId = activeSession.id,
                        content = formatUserFacingError(uiError),
                    )
                } else {
                    finalizeStreamingMessage(
                        sessionId = activeSession.id,
                        messageId = assistantMessageId,
                        finalText = formatUserFacingError(uiError),
                        role = MessageRole.SYSTEM,
                        requestId = requestId,
                        finishReason = if (generationTimedOut) "timeout" else "runtime_error",
                        terminalEventSeen = true,
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(isSending = false),
                        runtime = state.runtime
                            .copy(
                                modelRuntimeStatus = ModelRuntimeStatus.ERROR,
                                modelStatusDetail = uiError.userMessage,
                            )
                            .withUiError(uiError),
                    )
                }
                persistState()
            }
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
        launchStartupProbe(statusDetailOverride)
    }

    private fun bootstrapState() {
        val runtimeBackend = runtimeFacade.runtimeBackend()
        val persisted = sessionPersistence.loadState()
        val restoredRoutingMode = runCatching { RoutingMode.valueOf(persisted.routingMode) }
            .getOrDefault(runtimeFacade.getRoutingMode())
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
                    runtimeBackend = runtimeBackend,
                    startupProbeState = StartupProbeState.RUNNING,
                    modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                    modelStatusDetail = "Running runtime startup checks...",
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
                runtimeBackend = runtimeBackend,
                startupProbeState = StartupProbeState.RUNNING,
                modelRuntimeStatus = ModelRuntimeStatus.LOADING,
                modelStatusDetail = "Running runtime startup checks...",
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
                        modelStatusDetail = "Running runtime startup checks...",
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
            val startupError = UiErrorMapper.startupFailure(startupChecks)
            val startupModelStatus = resolveModelStatusFromStartupChecks(startupChecks)
            val runtimeBackend = runtimeFacade.runtimeBackend()
            val nextProbeState = if (startupChecks.isEmpty()) {
                StartupProbeState.READY
            } else {
                StartupProbeState.BLOCKED
            }
            _uiState.update { state ->
                state.copy(
                    runtime = state.runtime.copy(
                        runtimeBackend = runtimeBackend,
                        startupProbeState = nextProbeState,
                        modelRuntimeStatus = startupModelStatus,
                        modelStatusDetail = if (startupChecks.isEmpty()) {
                            statusDetailOverride ?: readyStatusDetail(runtimeBackend)
                        } else {
                            startupChecks.firstOrNull() ?: "Runtime startup checks failed."
                        },
                        startupChecks = startupChecks,
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
                    message.copy(content = text, isStreaming = true)
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
                    )
                }
            }
            session.copy(
                messages = updatedMessages,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun appendSystemMessage(sessionId: String, content: String) {
        updateActiveSession(sessionId) { session ->
            session.copy(
                messages = session.messages + createMessage(
                    role = MessageRole.SYSTEM,
                    content = content,
                    kind = MessageKind.TEXT,
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

    private fun resolveMaxTokens(prompt: String): Int {
        return if (prompt.length >= LONG_PROMPT_LENGTH) {
            LONG_PROMPT_MAX_TOKENS
        } else {
            SHORT_PROMPT_MAX_TOKENS
        }
    }

    private fun isRuntimeReady(runtime: RuntimeUiState): Boolean {
        return runtime.startupProbeState == StartupProbeState.READY &&
            runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
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
    ): MessageUiModel {
        return MessageUiModel(
            id = "msg-${System.currentTimeMillis()}-${role.name.lowercase()}-${(0..999).random()}",
            role = role,
            content = content,
            timestampEpochMs = System.currentTimeMillis(),
            kind = kind,
            imagePath = imagePath,
            toolName = toolName,
            isStreaming = false,
            terminalEventSeen = true,
        )
    }

    companion object {
        private const val TITLE_MAX_CHARS = 42
        private const val LONG_PROMPT_LENGTH = 160
        private const val SHORT_PROMPT_MAX_TOKENS = 32
        private const val LONG_PROMPT_MAX_TOKENS = 96
        private const val ONBOARDING_LAST_PAGE = 2
        private const val DEFAULT_RUNTIME_GENERATION_TIMEOUT_MS = 90_000L
        private const val DEFAULT_RUNTIME_STARTUP_PROBE_TIMEOUT_MS = 30_000L
        private const val STREAM_UI_UPDATE_MIN_INTERVAL_MS = 80L
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
