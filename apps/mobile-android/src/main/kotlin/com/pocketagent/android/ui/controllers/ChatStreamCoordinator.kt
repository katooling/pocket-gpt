package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeGateway
import com.pocketagent.android.ui.state.StreamReducerState
import com.pocketagent.android.ui.state.StreamStateReducer
import com.pocketagent.android.ui.state.StreamTerminalState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.RuntimeGenerationTimeoutException
import com.pocketagent.runtime.StreamChatRequestV2
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ChatStreamCoordinator(
    private val terminalWatchdogGraceMs: Long = 10_000L,
    private val sendElapsedUpdateIntervalMs: Long = 1_000L,
    private val noFirstTokenWarnMs: Long = 150_000L,
    private val noFirstTokenStallMs: Long = 600_000L,
) {
    suspend fun collectStream(
        runtimeGateway: RuntimeGateway,
        request: StreamChatRequestV2,
        requestTimeoutMs: Long,
        streamReducer: StreamStateReducer,
        sendStartedAtMs: Long,
        onEvent: (ChatStreamEvent, StreamReducerState) -> Unit,
        onElapsed: (Long, String?) -> Unit,
        onBeforeTerminal: () -> Unit,
        onTerminal: (StreamTerminalState) -> Unit,
    ) = coroutineScope {
        val streamReducerLock = Any()
        var streamState = StreamReducerState.initial(requestId = request.requestId)

        fun reduce(block: (StreamReducerState) -> StreamReducerState): Pair<StreamTerminalState?, StreamReducerState> {
            synchronized(streamReducerLock) {
                val previous = streamState.terminal
                streamState = block(streamState)
                return previous to streamState
            }
        }

        fun hasTerminal(): Boolean = synchronized(streamReducerLock) { streamState.terminal != null }

        fun streamFirstTokenMs(): Long? = synchronized(streamReducerLock) { streamState.firstTokenMs }

        val elapsedTicker = launch {
            while (!hasTerminal()) {
                val elapsed = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                val slowState = when {
                    streamFirstTokenMs() != null -> null
                    elapsed >= noFirstTokenStallMs -> "Still working on this device. You can keep waiting, or tap Cancel to stop."
                    elapsed >= noFirstTokenWarnMs -> "Loading model and prefill can take longer on older phones. You can keep waiting or cancel."
                    else -> null
                }
                onElapsed(elapsed, slowState)
                delay(sendElapsedUpdateIntervalMs)
            }
        }

        var streamCollector: Job? = null
        val prefillTimeoutWatchdog = launch {
            delay(requestTimeoutMs + terminalWatchdogGraceMs)
            if (streamFirstTokenMs() != null || hasTerminal()) {
                return@launch
            }
            val (previousTerminal, nextState) = reduce { state ->
                streamReducer.onWatchdogTimeout(state)
            }
            if (previousTerminal != null || nextState.terminal == null || nextState.firstTokenMs != null) {
                return@launch
            }
            elapsedTicker.cancel()
            runtimeGateway.cancelGenerationByRequest(request.requestId)
            val terminal = nextState.terminal ?: return@launch
            onBeforeTerminal()
            onTerminal(terminal)
            streamCollector?.cancel()
        }

        streamCollector = launch {
            runCatching {
                runtimeGateway.streamChat(request).collect { event ->
                    if (hasTerminal()) {
                        this.cancel()
                        return@collect
                    }
                    val elapsed = (System.currentTimeMillis() - sendStartedAtMs).coerceAtLeast(0L)
                    val (previousTerminal, nextState) = reduce { state ->
                        streamReducer.onEvent(state = state, event = event, elapsedMs = elapsed)
                    }
                    if (previousTerminal != null) return@collect
                    if (nextState.firstTokenMs != null) {
                        prefillTimeoutWatchdog.cancel()
                    }
                    onEvent(event, nextState)
                    nextState.terminal?.let { terminal ->
                        prefillTimeoutWatchdog.cancel()
                        elapsedTicker.cancel()
                        onBeforeTerminal()
                        onTerminal(terminal)
                        this.cancel()
                    }
                }
            }.onFailure { error ->
                val (previousTerminal, nextState) = reduce { state ->
                    streamReducer.onFailure(state = state, error = error)
                }
                if (previousTerminal != null || nextState.terminal == null) return@onFailure
                prefillTimeoutWatchdog.cancel()
                elapsedTicker.cancel()
                val generationTimedOut = error is TimeoutCancellationException || error is RuntimeGenerationTimeoutException
                if (generationTimedOut) {
                    runtimeGateway.cancelGenerationByRequest(request.requestId)
                }
                val terminal = nextState.terminal ?: return@onFailure
                onBeforeTerminal()
                onTerminal(terminal)
            }
        }
        streamCollector.join()
        prefillTimeoutWatchdog.cancel()
    }

}
