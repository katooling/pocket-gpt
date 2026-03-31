package com.pocketagent.android.ui

import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.runtime.RuntimeLoadedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHeaderUiStateTest {
    @Test
    fun `error state hides load last used action`() {
        val lastUsedModel = RuntimeLoadedModel(
            modelId = "qwen3.5-0.8b-q4",
            modelVersion = "v1",
        )

        val uiState = deriveChatHeaderUiState(
            ModelLoadingState.Error(
                requestedModel = lastUsedModel,
                loadedModel = null,
                lastUsedModel = lastUsedModel,
                message = "Load failed",
                code = "LOAD_FAILED",
                detail = "boom",
                timestampMs = 1L,
            ),
        )

        assertEquals("qwen3.5-0.8b-q4 v1", uiState.lastUsedModelLabel)
        assertFalse(uiState.canLoadLastUsedModel)
    }

    @Test
    fun `idle state with last used model shows load last used action`() {
        val lastUsedModel = RuntimeLoadedModel(
            modelId = "qwen3.5-0.8b-q4",
            modelVersion = "v1",
        )

        val uiState = deriveChatHeaderUiState(
            ModelLoadingState.Idle(
                loadedModel = null,
                lastUsedModel = lastUsedModel,
                updatedAtEpochMs = 1L,
            ),
        )

        assertEquals("qwen3.5-0.8b-q4 v1", uiState.lastUsedModelLabel)
        assertTrue(uiState.canLoadLastUsedModel)
    }
}
