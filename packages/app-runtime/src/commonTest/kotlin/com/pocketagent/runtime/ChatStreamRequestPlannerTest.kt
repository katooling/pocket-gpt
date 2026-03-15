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

    @Test
    fun `sampling overrides replace profile defaults in effective config`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val overrides = SamplingOverrides(
            temperature = 1.5f,
            topP = 0.8f,
            topK = 100,
            repeatPenalty = 1.3f,
            frequencyPenalty = 0.5f,
            presencePenalty = 0.2f,
        )
        val prepared = planner.prepare(command(samplingOverrides = overrides))

        val config = prepared.plan.effectiveConfig
        assertEquals(1.5f, config.temperature)
        assertEquals(0.8f, config.topP)
        assertEquals(100, config.topK)
        assertEquals(1.3f, config.repeatPenalty)
        assertEquals(0.5f, config.frequencyPenalty)
        assertEquals(0.2f, config.presencePenalty)
    }

    @Test
    fun `null sampling overrides preserve profile defaults`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val overrides = SamplingOverrides(temperature = 1.2f)
        val prepared = planner.prepare(command(samplingOverrides = overrides))

        val config = prepared.plan.effectiveConfig
        assertEquals(1.2f, config.temperature)
        // Other fields remain at profile defaults
        assertEquals(0.95f, config.topP)
        assertEquals(40, config.topK)
    }

    @Test
    fun `sampling overrides maxTokens replaces profile default`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val overrides = SamplingOverrides(maxTokens = 4096)
        val prepared = planner.prepare(command(samplingOverrides = overrides))

        assertEquals(4096, prepared.runtimeRequest.maxTokens)
    }

    @Test
    fun `system prompt override prepends system message`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val overrides = SamplingOverrides(systemPrompt = "Always respond in French")
        val userMessage = InteractionMessage(
            id = "user-1",
            role = InteractionRole.USER,
            parts = listOf(InteractionContentPart.Text("Hello")),
        )
        val prepared = planner.prepare(command(
            samplingOverrides = overrides,
            messages = listOf(userMessage),
        ))

        val messages = prepared.runtimeRequest.messages
        assertEquals(2, messages.size)
        assertEquals(InteractionRole.SYSTEM, messages[0].role)
        assertEquals("Always respond in French", (messages[0].parts[0] as InteractionContentPart.Text).text)
        assertEquals(InteractionRole.USER, messages[1].role)
    }

    @Test
    fun `blank system prompt does not prepend system message`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val overrides = SamplingOverrides(systemPrompt = "  ")
        val prepared = planner.prepare(command(samplingOverrides = overrides))

        assertTrue(prepared.runtimeRequest.messages.none { it.role == InteractionRole.SYSTEM })
    }

    @Test
    fun `no sampling overrides leaves config and messages unchanged`() {
        val planner = ChatStreamRequestPlanner(runtimeGenerationTimeoutMs = 0L)
        val prepared = planner.prepare(command(samplingOverrides = null))

        val config = prepared.plan.effectiveConfig
        assertEquals(0.6f, config.temperature)
        assertEquals(0.95f, config.topP)
        assertTrue(prepared.runtimeRequest.messages.isEmpty())
    }

    private fun command(
        requestId: String = "req-1",
        deviceState: DeviceState = DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8),
        keepAlivePreference: ChatKeepAlivePreference = ChatKeepAlivePreference.AUTO,
        samplingOverrides: SamplingOverrides? = null,
        messages: List<InteractionMessage> = emptyList(),
    ): ChatStreamCommand {
        return ChatStreamCommand(
            sessionId = SessionId("session-1"),
            requestId = requestId,
            messages = messages,
            promptHint = "short prompt",
            deviceState = deviceState,
            performanceProfile = RuntimePerformanceProfile.BALANCED,
            gpuEnabled = false,
            gpuQualifiedLayers = 0,
            keepAlivePreference = keepAlivePreference,
            samplingOverrides = samplingOverrides,
        )
    }
}
