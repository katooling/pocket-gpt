package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeTuning
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSendFlowTest {
    @Test
    fun `resolvePerformanceConfig applies persisted tuning overrides`() {
        val flow = ChatSendFlow(
            runtimeGenerationTimeoutMs = 0L,
            runtimeTuning = object : RuntimeTuning {
                override fun applyRecommendedConfig(
                    modelIdHint: String?,
                    baseConfig: com.pocketagent.runtime.PerformanceRuntimeConfig,
                    gpuQualifiedLayers: Int,
                ): com.pocketagent.runtime.PerformanceRuntimeConfig {
                    return baseConfig.copy(
                        gpuLayers = 4,
                        speculativeEnabled = false,
                        nBatch = 512,
                        nUbatch = 512,
                    )
                }
            },
        )

        val config = flow.resolvePerformanceConfig(
            profile = RuntimePerformanceProfile.FAST,
            gpuEnabled = true,
            gpuLayers = 8,
            modelIdHint = "model-a",
        )

        assertEquals(4, config.gpuLayers)
        assertEquals(false, config.speculativeEnabled)
        assertEquals(256, config.nBatch)
        assertEquals(256, config.nUbatch)
    }

    @Test
    fun `resolvePerformanceConfig applies qualified gpu layer budget`() {
        val flow = ChatSendFlow(runtimeGenerationTimeoutMs = 0L)

        val config = flow.resolvePerformanceConfig(
            profile = RuntimePerformanceProfile.BALANCED,
            gpuEnabled = true,
            gpuLayers = 8,
        )

        assertEquals(8, config.gpuLayers)
    }

    @Test
    fun `resolvePerformanceConfig caps gpu batches to safe defaults`() {
        val flow = ChatSendFlow(runtimeGenerationTimeoutMs = 0L)

        val config = flow.resolvePerformanceConfig(
            profile = RuntimePerformanceProfile.FAST,
            gpuEnabled = true,
            gpuLayers = 16,
        )

        assertEquals(256, config.nBatch)
        assertEquals(256, config.nUbatch)
    }

    @Test
    fun `resolvePerformanceConfig clamps negative gpu layer budget`() {
        val flow = ChatSendFlow(runtimeGenerationTimeoutMs = 0L)

        val config = flow.resolvePerformanceConfig(
            profile = RuntimePerformanceProfile.BALANCED,
            gpuEnabled = true,
            gpuLayers = -3,
        )

        assertEquals(0, config.gpuLayers)
    }
}
