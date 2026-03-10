package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceProfilesTest {
    @Test
    fun `balanced and fast presets keep ubatch below batch and fast expands context`() {
        val balanced = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )
        val fast = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        assertEquals(512, balanced.nBatch)
        assertEquals(256, balanced.nUbatch)
        assertEquals(2, balanced.speculativeDraftGpuLayers)
        assertEquals(true, balanced.useMmap)
        assertEquals(false, balanced.useMlock)
        assertEquals(128, balanced.nKeep)
        assertEquals(768, fast.nBatch)
        assertEquals(384, fast.nUbatch)
        assertEquals(4, fast.speculativeDraftGpuLayers)
        assertEquals(true, fast.useMmap)
        assertEquals(false, fast.useMlock)
        assertEquals(256, fast.nKeep)
        assertEquals(4096, fast.nCtx)
    }

    @Test
    fun `default residency policy allows low pressure devices to keep models resident for fifteen minutes`() {
        assertEquals(DEFAULT_MAX_IDLE_MODEL_UNLOAD_TTL_MS, ModelResidencyPolicy().idleUnloadTtlMs)
    }
}
