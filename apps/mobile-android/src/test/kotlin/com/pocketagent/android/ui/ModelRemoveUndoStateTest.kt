package com.pocketagent.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelRemoveUndoStateTest {
    @Test
    fun `initial state has empty hidden keys`() {
        val state = ModelRemoveUndoState(
            hiddenVersionKeys = emptySet(),
            requestRemove = { _, _ -> },
        )

        assertTrue(state.hiddenVersionKeys.isEmpty())
    }

    @Test
    fun `hidden version keys uses model-version composite key format`() {
        val state = ModelRemoveUndoState(
            hiddenVersionKeys = setOf("model-a::v1", "model-b::v2"),
            requestRemove = { _, _ -> },
        )

        assertTrue(state.hiddenVersionKeys.contains("model-a::v1"))
        assertTrue(state.hiddenVersionKeys.contains("model-b::v2"))
        assertEquals(2, state.hiddenVersionKeys.size)
    }

    @Test
    fun `request remove callback receives correct model id and version`() {
        var capturedModelId: String? = null
        var capturedVersion: String? = null
        val state = ModelRemoveUndoState(
            hiddenVersionKeys = emptySet(),
            requestRemove = { modelId, version ->
                capturedModelId = modelId
                capturedVersion = version
            },
        )

        state.requestRemove("test-model", "q4_0")

        assertEquals("test-model", capturedModelId)
        assertEquals("q4_0", capturedVersion)
    }

    @Test
    fun `hidden keys correctly filter using contains check`() {
        val state = ModelRemoveUndoState(
            hiddenVersionKeys = setOf("model-a::v1"),
            requestRemove = { _, _ -> },
        )

        assertTrue("model-a::v1" in state.hiddenVersionKeys)
        assertFalse("model-a::v2" in state.hiddenVersionKeys)
    }
}
