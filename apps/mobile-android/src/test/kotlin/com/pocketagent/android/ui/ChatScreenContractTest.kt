package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ChatGateState
import com.pocketagent.android.ui.state.ChatGateStatus
import com.pocketagent.android.ui.state.MessageKind
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenContractTest {
    @Test
    fun `inline gate card visibility follows chat gate status`() {
        val readyGate = ChatGateState(
            status = ChatGateStatus.READY,
            primaryAction = ChatGatePrimaryAction.NONE,
        )
        val blockedGate = ChatGateState(
            status = ChatGateStatus.BLOCKED_MODEL_MISSING,
            primaryAction = ChatGatePrimaryAction.OPEN_MODEL_SETUP,
        )

        assertFalse(shouldShowChatGateInlineCard(readyGate))
        assertTrue(shouldShowChatGateInlineCard(blockedGate))
    }

    @Test
    fun `chat gate action label mapping remains stable`() {
        assertEquals(
            com.pocketagent.android.R.string.ui_get_ready,
            chatGatePrimaryActionLabelResId(ChatGatePrimaryAction.GET_READY),
        )
        assertEquals(
            com.pocketagent.android.R.string.ui_open_model_setup,
            chatGatePrimaryActionLabelResId(ChatGatePrimaryAction.OPEN_MODEL_SETUP),
        )
        assertEquals(
            com.pocketagent.android.R.string.ui_refresh_runtime_checks,
            chatGatePrimaryActionLabelResId(ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS),
        )
        assertEquals(
            null,
            chatGatePrimaryActionLabelResId(ChatGatePrimaryAction.NONE),
        )
    }

    @Test
    fun `in-thread loading placeholder only applies to blank streaming assistant message`() {
        val streamingAssistant = MessageUiModel(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            content = "",
            timestampEpochMs = 1L,
            kind = MessageKind.TEXT,
            isStreaming = true,
        )
        val nonBlankStreamingAssistant = streamingAssistant.copy(content = "hello")
        val streamingUser = streamingAssistant.copy(
            id = "user-1",
            role = MessageRole.USER,
        )
        val nonStreamingAssistant = streamingAssistant.copy(isStreaming = false)

        assertTrue(shouldRenderInThreadLoadingPlaceholder(streamingAssistant))
        assertFalse(shouldRenderInThreadLoadingPlaceholder(nonBlankStreamingAssistant))
        assertFalse(shouldRenderInThreadLoadingPlaceholder(streamingUser))
        assertFalse(shouldRenderInThreadLoadingPlaceholder(nonStreamingAssistant))
    }
}
