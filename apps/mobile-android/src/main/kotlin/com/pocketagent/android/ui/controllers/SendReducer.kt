package com.pocketagent.android.ui.controllers

import com.pocketagent.android.ui.clearError
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamPhase

class SendReducer {
    fun onSendStarted(
        runtime: RuntimeUiState,
        toolDriven: Boolean,
    ): RuntimeUiState {
        if (toolDriven) {
            return runtime.clearError()
        }
        return runtime.copy(
            modelRuntimeStatus = ModelRuntimeStatus.LOADING,
            modelStatusDetail = "Loading model...",
            sendElapsedMs = 0L,
            sendSlowState = null,
        ).clearError()
    }

    fun statusDetailForEvent(event: ChatStreamEvent): String? {
        return when (event) {
            is ChatStreamEvent.Started -> "Preparing request..."
            is ChatStreamEvent.Phase -> when (event.phase) {
                ChatStreamPhase.CHAT_START -> "Preparing request..."
                ChatStreamPhase.MODEL_LOAD -> "Loading model..."
                ChatStreamPhase.PROMPT_PROCESSING -> "Prefill..."
                ChatStreamPhase.TOKEN_STREAM -> "Generating..."
                ChatStreamPhase.CHAT_END -> "Finalizing..."
                ChatStreamPhase.ERROR -> "Runtime error"
            }
            is ChatStreamEvent.TokenDelta -> "Generating..."
            is ChatStreamEvent.Completed -> "Completed"
            is ChatStreamEvent.Cancelled -> "Cancelled"
            is ChatStreamEvent.Failed -> "Runtime error"
        }
    }
}
