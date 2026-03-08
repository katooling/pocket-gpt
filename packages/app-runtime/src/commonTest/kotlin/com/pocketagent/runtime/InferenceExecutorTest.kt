package com.pocketagent.runtime

import com.pocketagent.inference.InferenceModule
import com.pocketagent.inference.InferenceRequest
import com.pocketagent.nativebridge.CachePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InferenceExecutorTest {
    @Test
    fun `executor trims stop sequence from final text and suppresses trailing deltas`() {
        val fakeInference = FakeInferenceModule(
            tokens = listOf("hello ", "world<|im_end|>", "ignored"),
        )
        val executor = InferenceExecutor(
            inferenceModule = fakeInference,
            runtimeConfig = testRuntimeConfig(),
        )
        val streamed = mutableListOf<String>()

        val result = executor.execute(
            sessionId = "s1",
            requestId = "r1",
            request = InferenceRequest(prompt = "prompt", maxTokens = 32),
            cacheKey = null,
            cachePolicy = CachePolicy.OFF,
            stopSequences = listOf("<|im_end|>"),
            onToken = { token -> streamed += token },
        )

        assertEquals("hello world", result.text)
        assertEquals("stop_sequence", result.finishReason)
        assertEquals(listOf("hello "), streamed)
    }

    @Test
    fun `cancel by request respects stream contract flag`() {
        val executor = InferenceExecutor(
            inferenceModule = FakeInferenceModule(emptyList()),
            runtimeConfig = testRuntimeConfig(streamContractV2Enabled = false),
        )
        assertFalse(executor.cancelByRequest("req"))
        assertFalse(executor.cancelBySession("session"))
        assertEquals("STREAM_CONTRACT_V2_DISABLED", executor.cancelByRequestDetailed("req").code)
    }
}

private class FakeInferenceModule(
    private val tokens: List<String>,
) : InferenceModule {
    override fun listAvailableModels(): List<String> = listOf("fake")

    override fun loadModel(modelId: String): Boolean = true

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        tokens.forEach { token -> onToken(token) }
    }

    override fun unloadModel() = Unit
}

private fun testRuntimeConfig(streamContractV2Enabled: Boolean = true): RuntimeConfig {
    return RuntimeConfig(
        artifactPayloadByModelId = emptyMap(),
        artifactFilePathByModelId = emptyMap(),
        artifactSha256ByModelId = emptyMap(),
        artifactProvenanceIssuerByModelId = emptyMap(),
        artifactProvenanceSignatureByModelId = emptyMap(),
        runtimeCompatibilityTag = "test",
        requireNativeRuntimeForStartupChecks = false,
        prefixCacheEnabled = false,
        prefixCacheStrict = false,
        responseCacheTtlSec = 0L,
        responseCacheMaxEntries = 0,
        streamContractV2Enabled = streamContractV2Enabled,
    )
}
