package com.pocketagent.android.ui.controllers

import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSendFlowTest {
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
