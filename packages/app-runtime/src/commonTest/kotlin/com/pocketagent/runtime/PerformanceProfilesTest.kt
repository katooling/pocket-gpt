package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceProfilesTest {
    @Test
    fun `battery profile always resolves to cpu-only even when gpu is requested`() {
        val battery = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BATTERY,
            availableCpuThreads = 8,
            gpuEnabled = true,
            gpuLayers = 24,
        )

        assertEquals(false, battery.gpuEnabled)
        assertEquals(0, battery.gpuLayers)
        assertEquals(0, battery.speculativeDraftGpuLayers)
    }

    @Test
    fun `balanced and fast presets match ubatch to batch and fast expands context`() {
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
        assertEquals(512, balanced.nUbatch)
        assertEquals(1, balanced.speculativeDraftGpuLayers)
        assertEquals(0.05f, balanced.minP)
        assertEquals(64, balanced.repeatLastN)
        assertEquals(1.05f, balanced.repeatPenalty)
        assertEquals(com.pocketagent.nativebridge.KvCacheType.Q8_0, balanced.kvCacheTypeK)
        assertEquals(com.pocketagent.nativebridge.KvCacheType.Q4_0, balanced.kvCacheTypeV)
        assertEquals(true, balanced.useMmap)
        assertEquals(false, balanced.useMlock)
        assertEquals(128, balanced.nKeep)
        assertEquals(768, fast.nBatch)
        assertEquals(768, fast.nUbatch)
        assertEquals(2, fast.speculativeDraftGpuLayers)
        assertEquals(0.05f, fast.minP)
        assertEquals(64, fast.repeatLastN)
        assertEquals(1.05f, fast.repeatPenalty)
        assertEquals(com.pocketagent.nativebridge.KvCacheType.Q8_0, fast.kvCacheTypeK)
        assertEquals(com.pocketagent.nativebridge.KvCacheType.Q4_0, fast.kvCacheTypeV)
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
