package com.pocketagent.runtime

import com.pocketagent.core.ObservabilityModule
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.nativebridge.BridgeError
import com.pocketagent.nativebridge.CachePolicy
import com.pocketagent.nativebridge.GenerationFinishReason
import com.pocketagent.nativebridge.GenerationResult
import com.pocketagent.nativebridge.LlamaCppInferenceModule
import com.pocketagent.nativebridge.LlamaCppRuntimeBridge
import com.pocketagent.nativebridge.RuntimeBackend
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeWarmupCoordinatorTest {
    @Test
    fun `warmup keeps active model resident and reuses same load key`() {
        val bridge = WarmupFakeBridge()
        val inferenceModule = LlamaCppInferenceModule(bridge)
        val runtimeConfig = warmupRuntimeConfig()
        val artifactVerifier = ArtifactVerifier(runtimeConfig)
        val observability = WarmupObservabilityModule()
        val coordinator = RuntimeWarmupCoordinator(
            inferenceModule = inferenceModule,
            artifactVerifier = artifactVerifier,
            observabilityModule = observability,
            cancelIdleUnload = {},
            scheduleIdleUnload = {},
            unloadNow = {},
            availableCpuThreads = { 6 },
            nowMs = monotonicClock(),
        )

        val first = coordinator.warmup()
        val second = coordinator.warmup()

        assertTrue(first.attempted)
        assertTrue(first.warmed)
        assertTrue(second.attempted)
        assertTrue(second.warmed)
        assertTrue(second.residentHit)
        assertEquals(1, bridge.loadCalls)
        assertEquals(2, bridge.generateCalls)
        assertTrue(observability.metrics.keys.contains("inference.model_load_ms"))
        assertTrue(observability.metrics.keys.contains("inference.warmup_ms"))
        assertTrue(observability.metrics.keys.contains("inference.resident_hit"))
    }
}

private class WarmupFakeBridge : LlamaCppRuntimeBridge {
    var loadCalls: Int = 0
    var generateCalls: Int = 0

    override fun isReady(): Boolean = true

    override fun listAvailableModels(): List<String> = listOf(ModelCatalog.QWEN_3_5_0_8B_Q4)

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        loadCalls += 1
        return true
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        generateCalls += 1
        onToken("ok")
        return GenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = 1,
            firstTokenMs = 1L,
            totalMs = 2L,
            cancelled = false,
        )
    }

    override fun unloadModel() = Unit

    override fun runtimeBackend(): RuntimeBackend = RuntimeBackend.NATIVE_JNI

    override fun lastError(): BridgeError? = null
}

private class WarmupObservabilityModule : ObservabilityModule {
    val metrics: MutableMap<String, MutableList<Double>> = mutableMapOf()

    override fun recordLatencyMetric(name: String, valueMs: Double) {
        metrics.getOrPut(name) { mutableListOf() }.add(valueMs)
    }

    override fun recordThermalSnapshot(level: Int) = Unit

    override fun exportLocalDiagnostics(): String = "diag"
}

private fun warmupRuntimeConfig(): RuntimeConfig {
    val payload = "warmup-payload".encodeToByteArray()
    return RuntimeConfig(
        artifactPayloadByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to payload),
        artifactFilePathByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to ""),
        artifactSha256ByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to warmupSha256(payload)),
        artifactProvenanceIssuerByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release"),
        artifactProvenanceSignatureByModelId = mapOf(ModelCatalog.QWEN_3_5_0_8B_Q4 to "sig-0"),
        runtimeCompatibilityTag = "android-arm64-v8a",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = true,
        prefixCacheStrict = false,
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
    )
}

private fun warmupSha256(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun monotonicClock(): () -> Long {
    var tick = 1_000L
    return {
        tick += 5L
        tick
    }
}
