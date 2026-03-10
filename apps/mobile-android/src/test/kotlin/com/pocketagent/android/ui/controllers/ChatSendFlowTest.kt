package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.RuntimeTuning
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `buildStreamChatRequest maps auto keep alive to adaptive residency`() {
        val flow = ChatSendFlow(runtimeGenerationTimeoutMs = 0L)
        val request = flow.buildStreamChatRequest(
            sessionId = "s1",
            requestId = "r1",
            messages = emptyList(),
            taskTypeHint = "hello",
            performanceConfig = flow.resolvePerformanceConfig(
                profile = RuntimePerformanceProfile.BALANCED,
                gpuEnabled = false,
            ),
            requestTimeoutMs = 10_000L,
            keepAlivePreference = RuntimeKeepAlivePreference.AUTO,
        )

        assertTrue(request.residencyPolicy.keepLoadedWhileAppForeground)
        assertTrue(request.residencyPolicy.adaptiveIdleTtl)
    }

    @Test
    fun `buildStreamChatRequest maps unload immediately to non resident policy`() {
        val flow = ChatSendFlow(runtimeGenerationTimeoutMs = 0L)
        val request = flow.buildStreamChatRequest(
            sessionId = "s1",
            requestId = "r1",
            messages = emptyList(),
            taskTypeHint = "hello",
            performanceConfig = flow.resolvePerformanceConfig(
                profile = RuntimePerformanceProfile.BALANCED,
                gpuEnabled = false,
            ),
            requestTimeoutMs = 10_000L,
            keepAlivePreference = RuntimeKeepAlivePreference.UNLOAD_IMMEDIATELY,
        )

        assertFalse(request.residencyPolicy.keepLoadedWhileAppForeground)
        assertFalse(request.residencyPolicy.adaptiveIdleTtl)
        assertEquals(1L, request.residencyPolicy.idleUnloadTtlMs)
    }
}
