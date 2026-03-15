package com.pocketagent.android.ui

import androidx.lifecycle.viewModelScope
import com.pocketagent.android.ui.state.ComposerUiState
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedInteractionMessage
import com.pocketagent.android.ui.state.PersistedInteractionPart
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.android.ui.state.UiError
import com.pocketagent.core.RuntimeExecutionStats
import com.pocketagent.runtime.ChatKeepAlivePreference
import com.pocketagent.runtime.ChatStreamCommand
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.ChatStreamEvent
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun ChatViewModel.sendMessageInternal() {
    val snapshot = _uiState.value
    val activeSession = snapshot.activeSession ?: return
    val prompt = snapshot.composer.text.trim()
    if (prompt.isBlank() || snapshot.composer.isSending) {
        return
    }

    if (!sendFlow.isRuntimeReadyForSend(snapshot.runtime)) {
        val uiError = startupFlow.startupBlockError(snapshot.runtime)
        applyBlockedRuntimeGuardrail(
            sessionId = activeSession.id,
            uiError = uiError,
        )
        return
    }
    val currentDeviceState = deviceStateProvider.current()
    val attachedImages = snapshot.composer.attachedImages
    val userMessage = createMessage(
        role = MessageRole.USER,
        content = prompt,
        kind = if (attachedImages.isNotEmpty()) MessageKind.IMAGE else MessageKind.TEXT,
        imagePath = attachedImages.firstOrNull(),
        imagePaths = attachedImages,
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
    val preparedStream = runtimeFacade.prepareChatStream(
        ChatStreamCommand(
            sessionId = com.pocketagent.core.SessionId(activeSession.id),
            requestId = requestId,
            messages = transcriptMessages,
            promptHint = prompt,
            deviceState = currentDeviceState,
            performanceProfile = snapshot.runtime.performanceProfile,
            gpuEnabled = snapshot.runtime.gpuAccelerationEnabled,
            gpuQualifiedLayers = snapshot.runtime.gpuMaxQualifiedLayers.coerceAtLeast(0),
            modelIdHint = snapshot.runtime.activeModelId,
            previousResponseId = previousResponseId,
            keepAlivePreference = snapshot.runtime.keepAlivePreference.toRuntimeKeepAlivePreference(),
            requestTimeoutOverrideMs = runtimeGenerationTimeoutMs.takeIf { it > 0L },
        ),
    )
    val performanceConfig = preparedStream.plan.effectiveConfig
    val targetPerformanceConfig = preparedStream.plan.baseConfig
    val requestTimeoutMs = preparedStream.plan.requestTimeoutMs
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
            terminalModelId: String? = null,
            errorCode: String? = null,
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
            runtimeTuning.recordFailure(
                modelId = terminalModelId ?: snapshot.runtime.activeModelId,
                appliedConfig = performanceConfig,
                targetConfig = targetPerformanceConfig,
                errorCode = errorCode ?: terminalReason.removePrefix("failed:"),
                thermalThrottled = currentDeviceState.thermalLevel >= 5,
            )
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
            runCatching { runtimeFacade.gpuOffloadStatus() }
                .getOrNull()
                ?.let { probe -> updateRuntimeGpuProbeState(probe) }
            refreshRuntimeDiagnostics()
            persistState()
        }

        fun finalizeFromTerminal(terminal: StreamTerminalState) {
            if (terminal.uiError != null) {
                finalizeWithRuntimeError(
                    uiError = terminal.uiError,
                    terminalReason = terminal.finishReason,
                    terminalRequestId = terminal.requestId,
                    terminalEventSeen = terminal.terminalEventSeen,
                    terminalModelId = terminal.responseModelId,
                    errorCode = terminal.errorCode,
                )
                return
            }
            val finalText = terminal.responseText?.trim().orEmpty()
            val effectiveFirstToken = terminal.firstTokenMs
            val effectiveCompletion = terminal.completionMs ?: (System.currentTimeMillis() - sendStartedAtMs)
            val runtimeStats = terminal.runtimeStats
            val effectivePrefill = runtimeStats?.prefillMs ?: effectiveFirstToken
            val effectiveDecode = runtimeStats?.decodeMs ?: if (effectiveFirstToken != null) {
                (effectiveCompletion - effectiveFirstToken).coerceAtLeast(0L)
            } else {
                null
            }
            val tokensPerSecEstimate = runtimeStats?.tokensPerSec ?: if (!finalText.isBlank() && effectiveDecode != null && effectiveDecode > 0L) {
                val approxTokens = finalText.split(WHITESPACE_REGEX).count { it.isNotBlank() }
                if (approxTokens > 0) {
                    approxTokens.toDouble() / (effectiveDecode.toDouble() / 1000.0)
                } else {
                    null
                }
            } else {
                null
            }
            // Finalize the message with per-message generation metrics
            finalizeStreamingMessage(
                sessionId = activeSession.id,
                messageId = assistantMessageId,
                finalText = finalText,
                requestId = terminal.requestId,
                finishReason = terminal.finishReason,
                terminalEventSeen = terminal.terminalEventSeen,
                firstTokenMs = effectiveFirstToken,
                tokensPerSec = tokensPerSecEstimate,
                totalLatencyMs = effectiveCompletion,
            )
            val resolvedRuntimeStats = runtimeStats ?: RuntimeExecutionStats(
                prefillMs = effectivePrefill,
                decodeMs = effectiveDecode,
                tokensPerSec = tokensPerSecEstimate,
            )
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
                        lastPeakRssMb = runtimeStats?.peakRssMb,
                        sendElapsedMs = null,
                        sendSlowState = null,
                    ).clearError(),
                )
            }
            val runtimeGpuCeiling = listOfNotNull(
                resolvedRuntimeStats.estimatedMaxGpuLayers,
                resolvedRuntimeStats.modelLayerCount,
            ).minOrNull()
            val tunedAppliedConfig = performanceConfig.copy(
                gpuLayers = resolvedRuntimeStats.appliedGpuLayers ?: performanceConfig.gpuLayers,
                speculativeDraftGpuLayers =
                    resolvedRuntimeStats.appliedDraftGpuLayers ?: performanceConfig.speculativeDraftGpuLayers,
            )
            val tunedTargetConfig = targetPerformanceConfig.copy(
                gpuLayers = runtimeGpuCeiling?.let { minOf(targetPerformanceConfig.gpuLayers, it) }
                    ?: targetPerformanceConfig.gpuLayers,
                speculativeDraftGpuLayers = runtimeGpuCeiling?.let {
                    minOf(targetPerformanceConfig.speculativeDraftGpuLayers, it)
                } ?: targetPerformanceConfig.speculativeDraftGpuLayers,
            )
            runtimeTuning.recordSuccess(
                modelId = terminal.responseModelId ?: snapshot.runtime.activeModelId,
                appliedConfig = tunedAppliedConfig,
                targetConfig = tunedTargetConfig,
                runtimeStats = resolvedRuntimeStats,
                thermalThrottled = currentDeviceState.thermalLevel >= 5,
            )
            refreshRuntimeDiagnostics()
            persistState()
            maybeAdvanceAfterAssistantResponse()
        }

        streamCoordinator.collectStream(
            runtimeService = runtimeFacade,
            preparedStream = preparedStream,
            streamReducer = streamReducer,
            sendStartedAtMs = sendStartedAtMs,
            onEvent = { event, nextState ->
                if (event is ChatStreamEvent.Delta) {
                    when (val delta = event.delta) {
                        is ChatStreamDelta.TextDelta -> {
                            pendingStreamingText = nextState.accumulatedText
                            flushPendingStreamingText(triggerToken = delta.text)
                        }
                    }
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

private fun com.pocketagent.android.ui.state.RuntimeKeepAlivePreference.toRuntimeKeepAlivePreference(): ChatKeepAlivePreference {
    return when (this) {
        com.pocketagent.android.ui.state.RuntimeKeepAlivePreference.AUTO -> ChatKeepAlivePreference.AUTO
        com.pocketagent.android.ui.state.RuntimeKeepAlivePreference.ALWAYS -> ChatKeepAlivePreference.ALWAYS
        com.pocketagent.android.ui.state.RuntimeKeepAlivePreference.ONE_MINUTE -> ChatKeepAlivePreference.ONE_MINUTE
        com.pocketagent.android.ui.state.RuntimeKeepAlivePreference.FIVE_MINUTES -> ChatKeepAlivePreference.FIVE_MINUTES
        com.pocketagent.android.ui.state.RuntimeKeepAlivePreference.FIFTEEN_MINUTES -> ChatKeepAlivePreference.FIFTEEN_MINUTES
        com.pocketagent.android.ui.state.RuntimeKeepAlivePreference.UNLOAD_IMMEDIATELY -> ChatKeepAlivePreference.UNLOAD_IMMEDIATELY
    }
}
