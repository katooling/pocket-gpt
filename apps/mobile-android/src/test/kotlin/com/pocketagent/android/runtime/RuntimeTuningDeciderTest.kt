package com.pocketagent.android.runtime

import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeTuningDeciderTest {
    @Test
    fun `memory pressure reduces gpu layers and batch size`() {
        val decider = RuntimeTuningDecider(nowMs = { 42L })
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
            gpuLayers = 16,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = true,
                peakRssMb = 3200.0,
            ),
        )

        assertEquals(12, next.gpuLayers)
        assertEquals(384, next.nBatch)
        assertEquals(384, next.nUbatch)
        assertTrue(next.quantizedKvCache == true)
        assertEquals(42L, next.updatedAtEpochMs)
        assertEquals("demote_memory_pressure", next.lastDecision)
    }

    @Test
    fun `slow speculative run disables speculative decoding`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = false,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = true,
                firstTokenMs = 4500L,
                tokensPerSec = 4.0,
            ),
        )

        assertFalse(next.speculativeEnabled ?: true)
        assertEquals("demote_speculative", next.lastDecision)
    }

    @Test
    fun `gpu runtime failures demote gpu layers`() {
        val decider = RuntimeTuningDecider()
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 6,
            gpuEnabled = true,
            gpuLayers = 8,
        )

        val next = decider.nextRecommendation(
            current = null,
            appliedConfig = targetConfig,
            targetConfig = targetConfig,
            observation = RuntimeTuningObservation(
                success = false,
                errorCode = "JNI_RUNTIME_ERROR",
            ),
        )

        assertEquals(6, next.gpuLayers)
        assertEquals("demote_gpu_regression", next.lastDecision)
    }

    @Test
    fun `three benchmark-quality wins promote demoted gpu layers toward target`() {
        val decider = RuntimeTuningDecider(nowMs = { 100L })
        val targetConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 6,
            gpuEnabled = true,
            gpuLayers = 8,
        )
        val demoted = RuntimeTuningRecommendation(
            gpuLayers = 4,
            quantizedKvCache = true,
            speculativeEnabled = true,
            nBatch = 256,
            nUbatch = 256,
            targetGpuLayers = 8,
            targetSpeculativeEnabled = true,
            targetNBatch = 512,
            targetNUbatch = 512,
        )
        val appliedConfig = targetConfig.copy(gpuLayers = 4, nBatch = 256, nUbatch = 256)
        val observation = RuntimeTuningObservation(
            success = true,
            firstTokenMs = 1500L,
            tokensPerSec = 16.0,
            peakRssMb = 1600.0,
        )

        val first = decider.nextRecommendation(demoted, appliedConfig, targetConfig, observation)
        val second = decider.nextRecommendation(first, appliedConfig, targetConfig, observation)
        val third = decider.nextRecommendation(second, appliedConfig, targetConfig, observation)

        assertEquals(6, third.gpuLayers)
        assertEquals(1, third.promotionCount)
        assertEquals(0, third.benchmarkWinCount)
        assertEquals("promote_gpu_layers", third.lastDecision)
    }
}
