package com.pocketagent.nativebridge

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeJniLlamaCppBridgeTest {
    @Test
    fun `uses native runtime when library is available`() {
        val nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello")
        val fallback = FakeFallbackBridge()
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = fallback,
        )

        assertTrue(bridge.isReady())
        assertEquals(RuntimeBackend.NATIVE_JNI, bridge.runtimeBackend())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        val tokens = mutableListOf<String>()
        val result = bridge.generate(
            requestId = "req-native-1",
            prompt = "prompt",
            maxTokens = 16,
            cacheKey = "k1",
            cachePolicy = CachePolicy.PREFIX_KV_REUSE,
            onToken = { tokens.add(it) },
        )
        assertTrue(result.success)
        bridge.unloadModel()

        assertTrue(nativeApi.loadCalled)
        assertTrue(nativeApi.generateCalled)
        assertTrue(nativeApi.unloadCalled)
        assertEquals("k1", nativeApi.lastCacheKey)
        assertEquals(CachePolicy.PREFIX_KV_REUSE.code, nativeApi.lastCachePolicyCode)
        assertFalse(fallback.loadCalled)
        assertEquals(listOf("native ", "hello "), tokens)
    }

    @Test
    fun `falls back to adb bridge when native runtime is unavailable`() {
        val nativeApi = FakeNativeApi(initializeOk = false, loadOk = false, generatedText = "")
        val fallback = FakeFallbackBridge(ready = true, loadOk = true, generateOk = true)
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> error("missing native library") },
            fallbackBridge = fallback,
            fallbackEnabled = true,
        )

        assertTrue(bridge.isReady())
        assertEquals(RuntimeBackend.ADB_FALLBACK, bridge.runtimeBackend())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        val tokens = mutableListOf<String>()
        val result = bridge.generate(
            requestId = "req-fallback-1",
            prompt = "prompt",
            maxTokens = 16,
            cacheKey = "fallback-k1",
            cachePolicy = CachePolicy.PREFIX_KV_REUSE_STRICT,
            onToken = { tokens.add(it) },
        )
        assertTrue(result.success)
        bridge.unloadModel()

        assertTrue(fallback.loadCalled)
        assertTrue(fallback.generateCalled)
        assertTrue(fallback.unloadCalled)
        assertEquals("fallback-k1", fallback.lastCacheKey)
        assertEquals(CachePolicy.PREFIX_KV_REUSE_STRICT, fallback.lastCachePolicy)
    }

    @Test
    fun `is not ready when both native and fallback are unavailable`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(initializeOk = false, loadOk = false, generatedText = ""),
            libraryLoader = { _ -> error("missing native library") },
            fallbackBridge = FakeFallbackBridge(ready = false),
            fallbackEnabled = true,
        )

        assertFalse(bridge.isReady())
        assertEquals(RuntimeBackend.UNAVAILABLE, bridge.runtimeBackend())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
    }

    @Test
    fun `cancel generation delegates to active backend`() {
        val nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello")
        val nativeBridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
        )
        assertTrue(nativeBridge.isReady())
        assertTrue(nativeBridge.cancelGeneration())
        assertTrue(nativeApi.cancelCalled)

        val fallback = FakeFallbackBridge()
        val fallbackBridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(initializeOk = false, loadOk = false, generatedText = ""),
            libraryLoader = { _ -> error("missing native library") },
            fallbackBridge = fallback,
            fallbackEnabled = true,
        )
        assertTrue(fallbackBridge.isReady())
        assertTrue(fallbackBridge.cancelGeneration())
        assertTrue(fallback.cancelCalled)
    }

    @Test
    fun `jni exceptions are recorded with deterministic bridge error code`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "native hello",
                throwOnLoad = true,
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals("JNI_LOAD_EXCEPTION", bridge.lastError()?.code)
    }

    @Test
    fun `gpu load failure retries once with cpu layers`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = false,
            generatedText = "native hello",
            supportsGpuOffload = true,
            loadResults = mutableListOf(false, true),
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = true,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 32,
            ),
        )

        assertTrue(bridge.isReady())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(listOf(32, 0), nativeApi.loadGpuLayers)
        assertEquals(null, bridge.lastError())
    }

    @Test
    fun `gpu layers are clamped to cpu path when gpu offload is not allowed`() {
        val nativeApi = FakeNativeApi(
            initializeOk = true,
            loadOk = true,
            generatedText = "native hello",
            supportsGpuOffload = true,
        )
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
            gpuOffloadAllowed = false,
        )
        bridge.setRuntimeGenerationConfig(
            RuntimeGenerationConfig.default().copy(
                gpuEnabled = true,
                gpuLayers = 32,
            ),
        )

        assertTrue(bridge.isReady())
        assertFalse(bridge.supportsGpuOffload())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        assertEquals(listOf(0), nativeApi.loadGpuLayers)
    }

    @Test
    fun `invalid model path extension is rejected before native load`() {
        val nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello")
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.bin"))
        assertEquals("MODEL_PATH_INVALID", bridge.lastError()?.code)
        assertFalse(nativeApi.loadCalled)
    }

    @Test
    fun `vulkan diagnostics are returned when native runtime is active`() {
        val bridge = NativeJniLlamaCppBridge(
            nativeApi = FakeNativeApi(
                initializeOk = true,
                loadOk = true,
                generatedText = "ok",
                vulkanDiagnosticsJson = """{"compiled_backend":"vulkan"}""",
            ),
            libraryLoader = { _ -> },
            fallbackBridge = FakeFallbackBridge(),
            fallbackEnabled = false,
        )

        assertTrue(bridge.isReady())
        assertEquals("""{"compiled_backend":"vulkan"}""", bridge.vulkanDiagnosticsJson())
    }
}

private class FakeNativeApi(
    private val initializeOk: Boolean,
    private val loadOk: Boolean,
    private val generatedText: String,
    private val throwOnLoad: Boolean = false,
    private val supportsGpuOffload: Boolean = false,
    private val loadResults: MutableList<Boolean>? = null,
    private val vulkanDiagnosticsJson: String = "{}",
) : NativeJniLlamaCppBridge.NativeApi {
    var loadCalled = false
    var generateCalled = false
    var unloadCalled = false
    var cancelCalled = false
    var lastCacheKey: String? = null
    var lastCachePolicyCode: Int? = null
    val loadGpuLayers = mutableListOf<Int>()

    override fun initialize(): Boolean = initializeOk

    override fun loadModel(
        modelId: String,
        modelPath: String,
        nThreads: Int,
        nThreadsBatch: Int,
        nBatch: Int,
        nUbatch: Int,
        nGpuLayers: Int,
    ): Boolean {
        if (throwOnLoad) {
            error("simulated native load exception")
        }
        loadCalled = true
        loadGpuLayers += nGpuLayers
        return if (loadResults != null && loadResults.isNotEmpty()) {
            loadResults.removeAt(0)
        } else {
            loadOk
        }
    }

    override fun generateStream(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicyCode: Int,
        onToken: NativeJniLlamaCppBridge.NativeApi.TokenCallback,
    ): NativeJniLlamaCppBridge.NativeApi.StreamStatus {
        generateCalled = true
        lastCacheKey = cacheKey
        lastCachePolicyCode = cachePolicyCode
        generatedText
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { token -> onToken.onToken("$token ") }
        return NativeJniLlamaCppBridge.NativeApi.StreamStatus(GenerationFinishReason.COMPLETED)
    }

    override fun generate(prompt: String, maxTokens: Int, cacheKey: String?, cachePolicyCode: Int): String {
        generateCalled = true
        lastCacheKey = cacheKey
        lastCachePolicyCode = cachePolicyCode
        return generatedText
    }

    override fun unloadModel() {
        unloadCalled = true
    }

    override fun cancelGeneration(): Boolean {
        cancelCalled = true
        return true
    }

    override fun supportsGpuOffload(): Boolean = supportsGpuOffload

    override fun vulkanDiagnosticsJson(): String = vulkanDiagnosticsJson
}

private class FakeFallbackBridge(
    private val ready: Boolean = true,
    private val loadOk: Boolean = true,
    private val generateOk: Boolean = true,
) : LlamaCppRuntimeBridge {
    var loadCalled = false
    var generateCalled = false
    var unloadCalled = false
    var cancelCalled = false
    var lastCacheKey: String? = null
    var lastCachePolicy: CachePolicy? = null

    override fun isReady(): Boolean = ready

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    )

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        loadCalled = true
        return loadOk
    }

    override fun generate(
        requestId: String,
        prompt: String,
        maxTokens: Int,
        cacheKey: String?,
        cachePolicy: CachePolicy,
        onToken: (String) -> Unit,
    ): GenerationResult {
        generateCalled = true
        lastCacheKey = cacheKey
        lastCachePolicy = cachePolicy
        if (!generateOk) {
            return GenerationResult(
                finishReason = GenerationFinishReason.ERROR,
                tokenCount = 0,
                firstTokenMs = -1L,
                totalMs = 0L,
                cancelled = false,
                errorCode = "FALLBACK_FAILURE",
            )
        }
        onToken("fallback ")
        onToken("token ")
        return GenerationResult(
            finishReason = GenerationFinishReason.COMPLETED,
            tokenCount = 2,
            firstTokenMs = 1L,
            totalMs = 2L,
            cancelled = false,
            errorCode = null,
        )
    }

    override fun unloadModel() {
        unloadCalled = true
    }

    override fun cancelGeneration(): Boolean {
        cancelCalled = true
        return true
    }

    override fun runtimeBackend(): RuntimeBackend {
        return if (ready) RuntimeBackend.ADB_FALLBACK else RuntimeBackend.UNAVAILABLE
    }
}
