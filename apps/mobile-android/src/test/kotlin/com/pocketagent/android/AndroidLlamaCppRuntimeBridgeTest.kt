package com.pocketagent.android

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLlamaCppRuntimeBridgeTest {
    @Test
    fun `uses native runtime when library is available`() {
        val nativeApi = FakeNativeApi(initializeOk = true, loadOk = true, generatedText = "native hello")
        val fallback = FakeFallbackBridge()
        val bridge = AndroidLlamaCppRuntimeBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> },
            fallbackBridge = fallback,
        )

        assertTrue(bridge.isReady())
        assertEquals(RuntimeBackend.NATIVE_JNI, bridge.runtimeBackend())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        val tokens = mutableListOf<String>()
        assertTrue(bridge.generate("prompt", 16) { tokens.add(it) })
        bridge.unloadModel()

        assertTrue(nativeApi.loadCalled)
        assertTrue(nativeApi.generateCalled)
        assertTrue(nativeApi.unloadCalled)
        assertFalse(fallback.loadCalled)
        assertEquals(listOf("native ", "hello "), tokens)
    }

    @Test
    fun `falls back to adb bridge when native runtime is unavailable`() {
        val nativeApi = FakeNativeApi(initializeOk = false, loadOk = false, generatedText = "")
        val fallback = FakeFallbackBridge(ready = true, loadOk = true, generateOk = true)
        val bridge = AndroidLlamaCppRuntimeBridge(
            nativeApi = nativeApi,
            libraryLoader = { _ -> error("missing native library") },
            fallbackBridge = fallback,
            fallbackEnabled = true,
        )

        assertTrue(bridge.isReady())
        assertEquals(RuntimeBackend.ADB_FALLBACK, bridge.runtimeBackend())
        assertTrue(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
        val tokens = mutableListOf<String>()
        assertTrue(bridge.generate("prompt", 16) { tokens.add(it) })
        bridge.unloadModel()

        assertTrue(fallback.loadCalled)
        assertTrue(fallback.generateCalled)
        assertTrue(fallback.unloadCalled)
    }

    @Test
    fun `is not ready when both native and fallback are unavailable`() {
        val bridge = AndroidLlamaCppRuntimeBridge(
            nativeApi = FakeNativeApi(initializeOk = false, loadOk = false, generatedText = ""),
            libraryLoader = { _ -> error("missing native library") },
            fallbackBridge = FakeFallbackBridge(ready = false),
            fallbackEnabled = true,
        )

        assertFalse(bridge.isReady())
        assertEquals(RuntimeBackend.UNAVAILABLE, bridge.runtimeBackend())
        assertFalse(bridge.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4, "/tmp/qwen-0.8b.gguf"))
    }
}

private class FakeNativeApi(
    private val initializeOk: Boolean,
    private val loadOk: Boolean,
    private val generatedText: String,
) : AndroidLlamaCppRuntimeBridge.NativeApi {
    var loadCalled = false
    var generateCalled = false
    var unloadCalled = false

    override fun initialize(): Boolean = initializeOk

    override fun loadModel(modelId: String, modelPath: String): Boolean {
        loadCalled = true
        return loadOk
    }

    override fun generate(prompt: String, maxTokens: Int): String {
        generateCalled = true
        return generatedText
    }

    override fun unloadModel() {
        unloadCalled = true
    }
}

private class FakeFallbackBridge(
    private val ready: Boolean = true,
    private val loadOk: Boolean = true,
    private val generateOk: Boolean = true,
) : LlamaCppRuntimeBridge {
    var loadCalled = false
    var generateCalled = false
    var unloadCalled = false

    override fun isReady(): Boolean = ready

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    )

    override fun loadModel(modelId: String, modelPath: String?): Boolean {
        loadCalled = true
        return loadOk
    }

    override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): Boolean {
        generateCalled = true
        if (!generateOk) {
            return false
        }
        onToken("fallback ")
        onToken("token ")
        return true
    }

    override fun unloadModel() {
        unloadCalled = true
    }

    override fun runtimeBackend(): RuntimeBackend {
        return if (ready) RuntimeBackend.ADB_FALLBACK else RuntimeBackend.UNAVAILABLE
    }
}
