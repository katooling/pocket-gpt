package com.pocketagent.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pocketagent.android.RoutingMode
import com.pocketagent.android.ui.runtime.ChatStreamEvent
import com.pocketagent.android.ui.runtime.MvpRuntimeFacade
import com.pocketagent.android.ui.runtime.StreamUserMessageRequest
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.PersistedChatState
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.SessionPersistence
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.android.ui.state.UiErrorMapper
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val runtimeFacade: MvpRuntimeFacade,
    private val sessionPersistence: SessionPersistence,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

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

        val userMessage = createMessage(
            role = MessageRole.USER,
            content = prompt,
            kind = MessageKind.TEXT,
        )
        val assistantMessageId = "assistant-stream-${System.currentTimeMillis()}"
        val assistantPlaceholder = MessageUiModel(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            content = "",
            timestampEpochMs = System.currentTimeMillis(),
            kind = MessageKind.TEXT,
            isStreaming = true,
        )

        updateActiveSession(activeSession.id) { session ->
            val updatedMessages = session.messages + userMessage + assistantPlaceholder
            session.copy(
                messages = updatedMessages,
                updatedAtEpochMs = System.currentTimeMillis(),
                title = deriveSessionTitle(updatedMessages),
            )
        }
        _uiState.update { state ->
            state.copy(
                composer = ComposerUiState(text = "", isSending = true),
                runtime = state.runtime.clearError(),
            )
        }
        persistState()

        viewModelScope.launch(ioDispatcher) {
            runCatching {
                runtimeFacade.streamUserMessage(
                    StreamUserMessageRequest(
                        sessionId = SessionId(activeSession.id),
                        userText = prompt,
                        taskType = resolveTaskType(prompt),
                        maxTokens = if (prompt.length >= LONG_PROMPT_LENGTH) 256 else 128,
                        deviceState = DEFAULT_DEVICE_STATE,
                    ),
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Token -> {
                            updateStreamingMessage(
                                sessionId = activeSession.id,
                                messageId = assistantMessageId,
                                text = event.accumulatedText,
                            )
                        }

                        is ChatStreamEvent.Completed -> {
                            finalizeStreamingMessage(
                                sessionId = activeSession.id,
                                messageId = assistantMessageId,
                                finalText = event.response.text,
                            )
                            _uiState.update { state ->
                                state.copy(
                                    composer = state.composer.copy(isSending = false),
                                    runtime = state.runtime.copy(
                                        activeModelId = event.response.modelId,
                                        lastFirstTokenLatencyMs = event.response.firstTokenLatencyMs,
                                        lastTotalLatencyMs = event.response.totalLatencyMs,
                                    ).clearError(),
                                )
                            }
                            persistState()
                        }
                    }
                }
            }.onFailure { error ->
                val uiError = UiErrorMapper.runtimeFailure(error.message)
                finalizeStreamingMessage(
                    sessionId = activeSession.id,
                    messageId = assistantMessageId,
                    finalText = formatUserFacingError(uiError),
                    role = MessageRole.SYSTEM,
                )
                _uiState.update { state ->
                    state.copy(
                        composer = state.composer.copy(isSending = false),
                        runtime = state.runtime.withUiError(uiError),
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
        val activeSession = _uiState.value.activeSession ?: return
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
                runtime = it.runtime.clearError(),
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
                            runtime = state.runtime.withUiError(mappedError),
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
                        runtime = state.runtime.clearError(),
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
                        runtime = state.runtime.withUiError(uiError),
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
                runtime = it.runtime.clearError(),
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
                                runtime = state.runtime.withUiError(mappedError),
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
                            runtime = state.runtime.clearError(),
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
                            runtime = state.runtime.withUiError(uiError),
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

    private fun bootstrapState() {
        val startupChecks = runtimeFacade.runStartupChecks()
        val startupError = UiErrorMapper.startupFailure(startupChecks)
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
                    startupChecks = startupChecks,
                ).withUiError(startupError),
            )
            persistState()
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
                startupChecks = startupChecks,
            ).withUiError(startupError),
        )
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
            ),
        )
    }

    private fun resolveTaskType(prompt: String): String {
        return if (prompt.length >= LONG_PROMPT_LENGTH) "long_text" else "short_text"
    }

    private fun deriveSessionTitle(messages: List<MessageUiModel>): String {
        val firstUserMessage = messages.firstOrNull { it.role == MessageRole.USER } ?: return "New chat"
        return firstUserMessage.content.take(TITLE_MAX_CHARS).ifBlank { "New chat" }
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
        )
    }

    companion object {
        private const val TITLE_MAX_CHARS = 42
        private const val LONG_PROMPT_LENGTH = 160
        private val DEFAULT_DEVICE_STATE = DeviceState(
            batteryPercent = 85,
            thermalLevel = 3,
            ramClassGb = 8,
        )
    }
}

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
