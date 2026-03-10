package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.LlamaCppRuntimeBridge
import com.pocketagent.nativebridge.RuntimeBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RuntimePlanResolverTest {
    @Test
    fun `sampling-only overrides do not change prefix cache slot`() {
        val nativeInference = buildNativeInference()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8)
        val baseConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        val first = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = listOf("</s>"),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            nativeInference = nativeInference,
        )
        val second = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = listOf("</s>"),
            requestConfig = baseConfig.copy(temperature = 0.2f, topK = 8, topP = 0.6f),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            nativeInference = nativeInference,
        )

        assertEquals(first.prefixCacheSlotId, second.prefixCacheSlotId)
    }

    @Test
    fun `different sessions resolve different prefix cache slots`() {
        val nativeInference = buildNativeInference()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8)
        val baseConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        val first = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            nativeInference = nativeInference,
        )
        val second = resolver.resolve(
            sessionId = "session-2",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            nativeInference = nativeInference,
        )

        assertNotEquals(first.prefixCacheSlotId, second.prefixCacheSlotId)
    }

    @Test
    fun `load-affecting overrides change prefix cache slot`() {
        val nativeInference = buildNativeInference()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8)
        val baseConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.BALANCED,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )

        val first = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            nativeInference = nativeInference,
        )
        val second = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = baseConfig.copy(nCtx = 1024),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = deviceState,
            nativeInference = nativeInference,
        )

        assertNotEquals(first.prefixCacheSlotId, second.prefixCacheSlotId)
    }

    @Test
    fun `resolve gates speculative decoding for low memory devices and applies pressure aware keep alive`() {
        val nativeInference = buildNativeInference()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val requestConfig = PerformanceRuntimeConfig.forProfile(
            profile = RuntimePerformanceProfile.FAST,
            availableCpuThreads = 8,
            gpuEnabled = true,
        )
            .copy(speculativeEnabled = true, speculativeDraftModelId = ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M)

        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "long_text",
            stopSequences = emptyList(),
            requestConfig = requestConfig,
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 15, thermalLevel = 7, ramClassGb = 6),
            nativeInference = nativeInference,
        )

        assertFalse(plan.effectiveConfig.speculativeEnabled)
        assertEquals(1024, plan.effectiveConfig.nCtx)
        assertEquals(60_000L, plan.keepAliveMs)
    }

    @Test
    fun `resolve applies estimated gpu layer ceiling when native metadata is available`() {
        val nativeInference = buildNativeInference().also {
            it.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4)
        }
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(idleUnloadTtlMs = 600_000L),
            deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 12),
            nativeInference = nativeInference,
        )

        assertEquals(12, plan.effectiveConfig.gpuLayers)
        assertTrue(plan.diagnostics.contains("layer=gpu_layer_estimate"))
    }

    @Test
    fun `resolve honors explicit keep alive when adaptive ttl disabled`() {
        val nativeInference = buildNativeInference()
        val resolver = RuntimePlanResolver(availableCpuThreads = { 8 })
        val plan = resolver.resolve(
            sessionId = "session-1",
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            taskType = "short_text",
            stopSequences = emptyList(),
            requestConfig = PerformanceRuntimeConfig.forProfile(
                profile = RuntimePerformanceProfile.BALANCED,
                availableCpuThreads = 8,
                gpuEnabled = true,
            ),
            residencyPolicy = ModelResidencyPolicy(
                idleUnloadTtlMs = 5 * 60_000L,
                adaptiveIdleTtl = false,
            ),
            deviceState = DeviceState(batteryPercent = 10, thermalLevel = 7, ramClassGb = 4),
            nativeInference = nativeInference,
        )

        assertEquals(5 * 60_000L, plan.keepAliveMs)
    }

    private fun buildNativeInference(): LlamaCppInferenceModule {
        return LlamaCppInferenceModule(ResolverBridge()).also { module ->
            module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")
            module.registerModelPath(ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M, "/tmp/smollm2-135m.gguf")
        }
    }
}

private class ResolverBridge : LlamaCppRuntimeBridge {
    override fun isReady(): Boolean = true

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M,
    )

    override fun loadModel(modelId: String, modelPath: String?): Boolean = true

    override fun modelLayerCount(): Int? = 22

    override fun modelSizeBytes(): Long? = 1_200_000_000L

    override fun estimateMaxGpuLayers(nCtx: Int): Int? = if (nCtx <= 2048) 12 else 8

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: com.pocketagent.nativebridge.CachePolicy,
        onToken: (String) -> Unit,
    ) = error("unused")

    override fun unloadModel() = Unit

    override fun runtimeBackend(): RuntimeBackend = RuntimeBackend.NATIVE_JNI
}
