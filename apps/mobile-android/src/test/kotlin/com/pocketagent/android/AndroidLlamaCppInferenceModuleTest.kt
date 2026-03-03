package com.pocketagent.android

import com.pocketagent.inference.InferenceRequest
import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidLlamaCppInferenceModuleTest {
    @Test
    fun `generate stream requires loaded model`() {
        val module = AndroidLlamaCppInferenceModule(FakeBridge())

        assertFailsWith<IllegalStateException> {
            module.generateStream(InferenceRequest("hello", 32)) {}
        }
    }

    @Test
    fun `load generate unload delegates to runtime bridge`() {
        val bridge = FakeBridge()
        val module = AndroidLlamaCppInferenceModule(bridge)

        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        val tokens = mutableListOf<String>()
        module.generateStream(InferenceRequest("hello", 32)) { tokens.add(it) }
        module.unloadModel()

        assertEquals(1, bridge.loadCalls)
        assertEquals(1, bridge.generateCalls)
        assertEquals(1, bridge.unloadCalls)
        assertEquals(listOf("hello ", "from ", "llama "), tokens)
    }

    @Test
    fun `load fails for unknown model and runtime generation failure throws`() {
        val bridge = FakeBridge(generateOk = false)
        val module = AndroidLlamaCppInferenceModule(bridge)

        assertFalse(module.loadModel("unknown-model"))
        assertTrue(module.loadModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        val error = assertFailsWith<IllegalStateException> {
            module.generateStream(InferenceRequest("hello", 32)) {}
        }
        assertTrue(error.message?.contains("generation failed") == true)
    }
}

private class FakeBridge(
    private val generateOk: Boolean = true,
) : LlamaCppRuntimeBridge {
    var loadCalls: Int = 0
    var generateCalls: Int = 0
    var unloadCalls: Int = 0

    override fun isReady(): Boolean = true

    override fun listAvailableModels(): List<String> = listOf(
        ModelCatalog.QWEN_3_5_0_8B_Q4,
        ModelCatalog.QWEN_3_5_2B_Q4,
    )

    override fun loadModel(modelId: String): Boolean {
        loadCalls += 1
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
}
