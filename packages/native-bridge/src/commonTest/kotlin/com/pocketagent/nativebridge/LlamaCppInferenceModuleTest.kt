package com.pocketagent.nativebridge

import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlamaCppInferenceModuleTest {
    @Test
    fun `generate stream requires loaded model`() {
        val module = LlamaCppInferenceModule(FakeBridge())

        assertFailsWith<IllegalStateException> {
            module.generateStream(InferenceRequest("hello", 32)) {}
        }
    }

    @Test
    fun `load generate unload delegates to runtime bridge`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        val tokens = mutableListOf<String>()
        module.generateStream(InferenceRequest("hello", 32)) { tokens.add(it) }
        module.unloadModel()

        assertEquals(1, bridge.loadCalls)
        assertEquals(1, bridge.generateCalls)
        assertEquals(1, bridge.unloadCalls)
        assertEquals("/tmp/qwen-0.8b.gguf", bridge.lastModelPath)
        assertEquals(listOf("hello ", "from ", "llama "), tokens)
    }

    @Test
    fun `load fails for unknown model and runtime generation failure throws`() {
        val bridge = FakeBridge(generateOk = false)
        val module = LlamaCppInferenceModule(bridge)

        assertFalse(module.loadModel("unknown-model"))
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        val error = assertFailsWith<IllegalStateException> {
            module.generateStream(InferenceRequest("hello", 32)) {}
        }
        assertTrue(error.message?.contains("generation failed") == true)
    }


    @Test
    fun `last bridge error delegates to runtime bridge contract`() {
        val module = LlamaCppInferenceModule(FakeBridge(lastError = BridgeError("REMOTE_PROCESS_DIED", "service disconnected")))

        assertEquals("REMOTE_PROCESS_DIED", module.lastBridgeError()?.code)
    }

    @Test
    fun `runtime backend delegates to bridge`() {
        val module = LlamaCppInferenceModule(FakeBridge(backend = RuntimeBackend.ADB_FALLBACK))
        assertEquals(RuntimeBackend.ADB_FALLBACK, module.runtimeBackend())
    }

    @Test
    fun `generate stream with cache delegates cache key and policy`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        module.generateStreamWithCache(
            requestId = "req-cache",
            request = InferenceRequest("hello", 32),
            cacheKey = "cache-key-v1",
            cachePolicy = CachePolicy.PREFIX_KV_REUSE,
            onToken = {},
        )

        assertEquals("cache-key-v1", bridge.lastCacheKey)
        assertEquals(CachePolicy.PREFIX_KV_REUSE, bridge.lastCachePolicy)
    }

    @Test
    fun `changing runtime generation config reloads active model on next load`() {
        val bridge = FakeBridge()
        val module = LlamaCppInferenceModule(bridge)
        module.registerModelPath(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf")

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        module.setRuntimeGenerationConfig(
            RuntimeGenerationConfig(
                nThreads = 6,
                nThreadsBatch = 6,
                nBatch = 768,
                nUbatch = 768,
                gpuEnabled = true,
                gpuLayers = 32,
            ),
        )
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        assertEquals(2, bridge.loadCalls)
        assertEquals(1, bridge.unloadCalls)
    }
}

private class FakeBridge(
    private val generateOk: Boolean = true,
    private val backend: RuntimeBackend = RuntimeBackend.NATIVE_JNI,
    private val lastError: BridgeError? = null,
) : LlamaCppRuntimeBridge {
    var loadCalls: Int = 0
    var generateCalls: Int = 0
    var unloadCalls: Int = 0
    var lastModelPath: String? = null
    var lastCacheKey: String? = null
    var lastCachePolicy: CachePolicy? = null

    override fun isReady(): Boolean = true

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    )

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        loadCalls += 1
        lastModelPath = modelPath
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
        lastCacheKey = cacheKey
        lastCachePolicy = cachePolicy
        if (!generateOk) {
            return GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "FAKE_FAILURE",
            )
        }
        onToken("hello ")
        onToken("from ")
        onToken("llama ")
        return GenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = 3,
            firstTokenMs = 1L,
            totalMs = 3L,
            cancelled = false,
            errorCode = null,
        )
    }

    override fun unloadModel() {
        unloadCalls += 1
    }

    override fun runtimeBackend(): RuntimeBackend = backend

    override fun lastError(): BridgeError? = lastError
}
