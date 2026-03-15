package com.pocketagent.runtime

import com.pocketagent.core.SessionId
import com.pocketagent.inference.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatStreamRequestPlannerTest {
    @Test
    fun `resolve performance config applies recommended tuning and gpu safe batch caps`() {
        val planner = ChatStreamRequestPlanner(
            runtimeGenerationTimeoutMs = 0L,
            availableCpuThreadsProvider = { 8 },
            recommendedConfig = { _, baseConfig, _ ->
                baseConfig.copy(
                    gpuLayers = 4,
                    speculativeEnabled = false,
                    nBatch = 512,
                    nUbatch = 512,
                )
            },
        )

        val config = planner.resolvePerformanceConfig(
            profile = RuntimePerformanceProfile.FAST,
            gpuEnabled = true,
            gpuLayers = 8,
            modelIdHint = "model-a",
        )

        assertEquals(4, config.gpuLayers)
        assertFalse(config.speculativeEnabled)
        assertEquals(256, config.nBatch)
        assertEquals(256, config.nUbatch)
    }

    @Test
    fun `prepare maps keep alive and max tokens from provided config`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val prepared = planner.prepare(
            command = command(keepAlivePreference = ChatKeepAlivePreference.UNLOAD_IMMEDIATELY),
            performanceConfig = PerformanceRuntimeConfig.default().copy(maxTokensDefault = 160),
            requestTimeoutMs = 10_000L,
        )

        assertEquals(160, prepared.runtimeRequest.maxTokens)
        assertFalse(prepared.runtimeRequest.residencyPolicy.keepLoadedWhileAppForeground)
        assertEquals(1L, prepared.runtimeRequest.residencyPolicy.idleUnloadTtlMs)
        assertEquals(10_000L, prepared.plan.requestTimeoutMs)
    }

    @Test
    fun `prepare enforces minimum max tokens for tiny configs`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val prepared = planner.prepare(
            command = command(),
            performanceConfig = PerformanceRuntimeConfig.default().copy(maxTokensDefault = 4),
            requestTimeoutMs = 10_000L,
        )

        assertEquals(16, prepared.runtimeRequest.maxTokens)
    }

    @Test
    fun `prepare preserves provided device state and request id`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val deviceState = DeviceState(batteryPercent = 22, thermalLevel = 7, ramClassGb = 6)
        val prepared = planner.prepare(
            command = command(requestId = "req-123", deviceState = deviceState),
        )

        assertEquals("req-123", prepared.runtimeRequest.requestId)
        assertEquals(deviceState, prepared.runtimeRequest.deviceState)
        assertTrue(prepared.plan.requestTimeoutMs > 0L)
    }

    private fun command(
        requestId: String = "req-1",
        deviceState: DeviceState = DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8),
        keepAlivePreference: ChatKeepAlivePreference = ChatKeepAlivePreference.AUTO,
    ): ChatStreamCommand {
        return ChatStreamCommand(
            sessionId = SessionId("session-1"),
            requestId = requestId,
            messages = emptyList(),
            promptHint = "short prompt",
            deviceState = deviceState,
            performanceProfile = RuntimePerformanceProfile.BALANCED,
            gpuEnabled = false,
            gpuQualifiedLayers = 0,
            keepAlivePreference = keepAlivePreference,
        )
    }
}
