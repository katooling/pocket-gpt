package com.pocketagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeImageInputModuleTest {
    @Test
    fun `valid image request uses runtime stream output`() {
        val module = RuntimeImageInputModule(FakeInferenceModule(tokens = listOf("runtime ", "image ", "summary")))

        val result = module.analyzeImage(
            ImageRequest(
                imagePath = "/tmp/photo.jpg",
                prompt = "Describe this image",
                maxTokens = 64,
            ),
        )

        assertEquals("runtime image summary", result)
    }

    @Test
    fun `runtime generation failure returns deterministic error`() {
        val module = RuntimeImageInputModule(FakeInferenceModule(shouldFail = true))

        val result = module.analyzeImage(
            ImageRequest(
                imagePath = "/tmp/photo.jpg",
                prompt = "Describe this image",
                maxTokens = 64,
            ),
        )

        assertTrue(result.startsWith("IMAGE_RUNTIME_ERROR:RUNTIME_GENERATION_FAILED:"))
    }

    @Test
    fun `missing prompt and unsupported extension return deterministic validation errors`() {
        val module = RuntimeImageInputModule(FakeInferenceModule())

        assertEquals(
            "IMAGE_VALIDATION_ERROR:MISSING_PROMPT:prompt is required",
            module.analyzeImage(ImageRequest(imagePath = "photo.jpg", prompt = "   ", maxTokens = 16)),
        )
        assertEquals(
            "IMAGE_VALIDATION_ERROR:UNSUPPORTED_EXTENSION:extension 'gif' is not supported",
            module.analyzeImage(ImageRequest(imagePath = "photo.gif", prompt = "Describe", maxTokens = 16)),
        )
    }
}

private class FakeInferenceModule(
    private val tokens: List<String> = listOf("ok "),
    private val shouldFail: Boolean = false,
) : InferenceModule {
    override fun listAvailableModels(): List<String> = emptyList()

    override fun loadModel(modelId: String): Boolean = true

    override fun generateStream(request: InferenceRequest, onToken: (String) -> Unit) {
        if (shouldFail) {
            error("simulated runtime failure")
        }
        tokens.forEach(onToken)
    }

    override fun unloadModel() {
        // no-op for test
    }
}
