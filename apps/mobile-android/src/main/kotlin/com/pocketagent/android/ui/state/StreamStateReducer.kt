package com.pocketagent.android.ui.state

import com.pocketagent.core.RuntimeExecutionStats
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamDelta
import com.pocketagent.runtime.RuntimeGenerationTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

data class StreamReducerState(
    val requestId: String,
    val accumulatedText: String = "",
    val firstTokenMs: Long? = null,
    val lastPhase: String? = null,
    val terminal: StreamTerminalState? = null,
) {
    companion object {
        fun initial(requestId: String): StreamReducerState = StreamReducerState(requestId = requestId)
    }
}

data class StreamTerminalState(
    val requestId: String,
    val finishReason: String,
    val terminalEventSeen: Boolean,
    val uiError: UiError? = null,
    val responseText: String? = null,
    val responseModelId: String? = null,
    val completionMs: Long? = null,
    val firstTokenMs: Long? = null,
    val errorCode: String? = null,
    val runtimeStats: RuntimeExecutionStats? = null,
)

class StreamStateReducer(
    private val requestTimeoutMs: Long,
) {
    fun onEvent(
        state: StreamReducerState,
        event: ChatStreamEvent,
        elapsedMs: Long,
    ): StreamReducerState {
        if (state.terminal != null) {
            return state
        }
        return when (event) {
            is ChatStreamEvent.Started -> state
            is ChatStreamEvent.Phase -> state.copy(lastPhase = event.phase.name.lowercase())
            is ChatStreamEvent.Delta -> {
                when (event.delta) {
                    is ChatStreamDelta.TextDelta -> {
                        val firstToken = if (state.firstTokenMs == null && event.accumulatedText.isNotBlank()) {
                            elapsedMs.coerceAtLeast(0L)
                        } else {
                            state.firstTokenMs
                        }
                        state.copy(
                            accumulatedText = event.accumulatedText,
                            firstTokenMs = firstToken,
                        )
                    }
                }
            }
            is ChatStreamEvent.Completed -> {
                state.copy(
                    accumulatedText = event.response.text,
                    terminal = StreamTerminalState(
                        requestId = event.requestId,
                        finishReason = event.finishReason,
                        terminalEventSeen = event.terminalEventSeen,
                        responseText = event.response.text,
                        responseModelId = event.response.modelId,
                        completionMs = event.completionMs,
                        firstTokenMs = state.firstTokenMs ?: event.firstTokenMs,
                        runtimeStats = event.response.runtimeStats,
                    ),
                )
            }
            is ChatStreamEvent.Cancelled -> {
                val uiError = if (event.reason.equals("timeout", ignoreCase = true)) {
                    UiErrorMapper.runtimeTimeout(requestTimeoutMs)
                } else {
                    UiErrorMapper.runtimeCancelled(event.reason)
                }
                state.copy(
                    terminal = StreamTerminalState(
                        requestId = event.requestId,
                        finishReason = event.reason,
                        terminalEventSeen = event.terminalEventSeen,
                        uiError = uiError,
                        responseText = state.accumulatedText,
                        completionMs = event.completionMs,
                        firstTokenMs = state.firstTokenMs ?: event.firstTokenMs,
                    ),
                )
            }
            is ChatStreamEvent.Failed -> {
                state.copy(
                    terminal = StreamTerminalState(
                        requestId = event.requestId,
                        finishReason = "failed:${event.errorCode}",
                        terminalEventSeen = event.terminalEventSeen,
                        uiError = UiErrorMapper.runtimeFailure(event.message),
                        responseText = state.accumulatedText,
                        completionMs = event.completionMs,
                        firstTokenMs = state.firstTokenMs ?: event.firstTokenMs,
                        errorCode = event.errorCode,
                    ),
                )
            }
            else -> state
        }
    }

    fun onFailure(
        state: StreamReducerState,
        error: Throwable,
    ): StreamReducerState {
        if (state.terminal != null) {
            return state
        }
        val timedOut = error is TimeoutCancellationException || error is RuntimeGenerationTimeoutException
        val uiError = if (timedOut) {
            UiErrorMapper.runtimeTimeout(requestTimeoutMs)
        } else {
            UiErrorMapper.runtimeFailure(error.message)
        }
        val reason = if (timedOut) "timeout" else "runtime_error"
        return state.copy(
            terminal = StreamTerminalState(
                requestId = state.requestId,
                finishReason = reason,
                terminalEventSeen = true,
                uiError = uiError,
                responseText = state.accumulatedText,
            ),
        )
    }

    fun onWatchdogTimeout(state: StreamReducerState): StreamReducerState {
        if (state.terminal != null) {
            return state
        }
        return state.copy(
            terminal = StreamTerminalState(
                requestId = state.requestId,
                finishReason = "timeout",
                terminalEventSeen = true,
                uiError = UiErrorMapper.runtimeTimeout(requestTimeoutMs),
                responseText = state.accumulatedText,
            ),
        )
    }

}
