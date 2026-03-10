package com.pocketagent.android.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlamaRuntimeExecutionGateTest {
    @Test
    fun `non-streaming blocks probe and generation until fully drained`() {
        val gate = LlamaRuntimeExecutionGate()

        assertTrue(gate.tryBeginNonStreaming())
        assertTrue(gate.tryBeginNonStreaming())
        assertFalse(gate.tryBeginProbe())
        assertFalse(gate.tryBeginGeneration())

        gate.endNonStreaming()
        assertFalse(gate.tryBeginProbe())
        assertFalse(gate.tryBeginGeneration())

        gate.endNonStreaming()
        assertTrue(gate.tryBeginProbe())
        gate.endProbe()
        assertTrue(gate.tryBeginGeneration())
        gate.endGeneration()
    }

    @Test
    fun `config path is busy only for active generation or probe`() {
        val gate = LlamaRuntimeExecutionGate()

        assertFalse(gate.isBusyForConfig())
        assertTrue(gate.tryBeginNonStreaming())
        assertFalse(gate.isBusyForConfig())
        gate.endNonStreaming()

        assertTrue(gate.tryBeginGeneration())
        assertTrue(gate.isBusyForConfig())
        gate.endGeneration()
        assertFalse(gate.isBusyForConfig())

        assertTrue(gate.tryBeginProbe())
        assertTrue(gate.isBusyForConfig())
        gate.endProbe()
        assertFalse(gate.isBusyForConfig())
    }
}
