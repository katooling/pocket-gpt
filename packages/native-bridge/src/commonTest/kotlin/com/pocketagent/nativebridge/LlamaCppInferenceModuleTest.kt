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
    fun `runtime backend delegates to bridge`() {
        val module = LlamaCppInferenceModule(FakeBridge(backend = RuntimeBackend.ADB_FALLBACK))
        assertEquals(RuntimeBackend.ADB_FALLBACK, module.runtimeBackend())
    }
}

private class FakeBridge(
    private val generateOk: Boolean = true,
    private val backend: RuntimeBackend = RuntimeBackend.NATIVE_JNI,
) : LlamaCppRuntimeBridge {
    var loadCalls: Int = 0
    var generateCalls: Int = 0
    var unloadCalls: Int = 0
    var lastModelPath: String? = null

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

    override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): Boolean {
        generateCalls += 1
        if (!generateOk) {
            return false
        }
        onToken("hello ")
        onToken("from ")
        onToken("llama ")
        return true
    }

    override fun unloadModel() {
        unloadCalls += 1
    }

    override fun runtimeBackend(): RuntimeBackend = backend
}
