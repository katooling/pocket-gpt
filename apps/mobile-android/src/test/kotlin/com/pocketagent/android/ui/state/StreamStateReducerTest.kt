package com.pocketagent.android.ui.state

import com.pocketagent.core.ChatResponse
import com.pocketagent.runtime.ChatStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StreamStateReducerTest {
    private val reducer = StreamStateReducer(requestTimeoutMs = 30_000L)

    @Test
    fun `reducer accepts only first terminal event`() {
        val initial = StreamReducerState.initial(requestId = "req-1")
        val afterCompleted = reducer.onEvent(
            state = initial,
            event = ChatStreamEvent.Completed(
                requestId = "req-1",
                response = ChatResponse(
                    sessionId = com.pocketagent.core.SessionId("s1"),
                    modelId = "qwen",
                    text = "hello",
                    firstTokenLatencyMs = 10,
                    totalLatencyMs = 20,
                    requestId = "req-1",
                    finishReason = "completed",
                ),
                finishReason = "completed",
            ),
            elapsedMs = 20L,
        )
        val afterFailed = reducer.onEvent(
            state = afterCompleted,
            event = ChatStreamEvent.Failed(
                requestId = "req-1",
                errorCode = "runtime_error",
                message = "late failure",
            ),
            elapsedMs = 25L,
        )

        assertNotNull(afterCompleted.terminal)
        assertEquals("completed", afterCompleted.terminal?.finishReason)
        assertEquals(afterCompleted, afterFailed)
    }

    @Test
    fun `reducer maps timeout cancellation deterministically`() {
        val initial = StreamReducerState.initial(requestId = "req-1")
        val terminal = reducer.onEvent(
            state = initial,
            event = ChatStreamEvent.Cancelled(
                requestId = "req-1",
                reason = "timeout",
            ),
            elapsedMs = 100L,
        ).terminal

        assertNotNull(terminal)
        assertEquals("timeout", terminal.finishReason)
        assertEquals("UI-RUNTIME-001", terminal.uiError?.code)
    }

    @Test
    fun `reducer tracks phase updates without creating terminal state`() {
        val initial = StreamReducerState.initial(requestId = "req-1")
        val afterPhase = reducer.onEvent(
            state = initial,
            event = ChatStreamEvent.Phase(
                requestId = "req-1",
                phase = com.pocketagent.runtime.ChatStreamPhase.PROMPT_PROCESSING,
                detail = "prefill",
            ),
            elapsedMs = 10L,
        )

        assertEquals("prompt_processing", afterPhase.lastPhase)
        assertEquals(null, afterPhase.terminal)
    }
}
