package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeResidencyManagerTest {
    @Test
    fun `ensureLoaded tracks resident slot and explicit unload clears it`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        assertTrue(manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 1_000L))
        assertEquals(1, inferenceModule.loadCalls)
        assertEquals("model-a", manager.listResident().single().modelId)
        assertEquals("slot-1", manager.listResident().single().slotId)

        assertTrue(manager.unload(reason = "manual"))
        assertEquals(1, inferenceModule.unloadCalls)
        assertTrue(manager.listResident().isEmpty())
    }

    @Test
    fun `critical trim memory unloads resident model`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 120_000L)
        assertTrue(manager.onTrimMemory(level = 15))

        assertEquals(1, inferenceModule.unloadCalls)
        assertTrue(manager.listResident().isEmpty())
    }

    @Test
    fun `moderate trim memory shortens keep alive without unloading`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 300_000L)

        assertTrue(manager.onTrimMemory(level = 5))

        assertEquals(0, inferenceModule.unloadCalls)
        val resident = manager.listResident().single()
        assertEquals(60_000L, resident.keepAliveMs)
        assertFalse(resident.expiresAtEpochMs <= resident.lastTouchedAtEpochMs)
    }

    @Test
    fun `generation lifecycle updates queue depth and preserves slot residency`() {
        val inferenceModule = RecordingInferenceModule()
        val manager = RuntimeResidencyManager(inferenceModule, nowMs = advancingClock())

        manager.ensureLoaded(modelId = "model-a", slotId = "slot-1", keepAliveMs = 1_000L)
        manager.onGenerationStarted()
        assertEquals(1, manager.queueDepth())

        manager.onGenerationFinished(slotId = "slot-1", keepAliveMs = 2_000L)

        assertEquals(0, manager.queueDepth())
        assertEquals("slot-1", manager.listResident().single().slotId)
    }
}

private class RecordingInferenceModule : InferenceModule {
    var loadCalls: Int = 0
    var unloadCalls: Int = 0

    override fun listAvailableModels(): List<String> = listOf("model-a")

    override fun loadModel(modelId: String): Boolean {
        loadCalls += 1
        return true
    }

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) = Unit

    override fun unloadModel() {
        unloadCalls += 1
    }
}

private fun advancingClock(): () -> Long {
    var tick = 1_000L
    return {
        tick += 10L
        tick
    }
}
