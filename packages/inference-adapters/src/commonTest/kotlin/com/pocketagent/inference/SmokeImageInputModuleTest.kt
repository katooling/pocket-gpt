package com.pocketagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmokeImageInputModuleTest {
    private val module = SmokeImageInputModule()

    @Test
    fun `normalizes extension and prompt whitespace`() {
        val imagePath = "/tmp/scan.DOCX"
        val output = module.analyzeImage(
            ImageRequest(
                imagePath = imagePath,
                prompt = "   summarize\nthis\tpage   quickly   ",
                maxTokens = 128,
            ),
        )

        assertEquals("IMAGE_VALIDATION_ERROR:UNSUPPORTED_EXTENSION:extension 'docx' is not supported", output)
        assertFalse(output.contains(imagePath))
    }

    @Test
    fun `uses unknown extension when no extension is present`() {
        val output = module.analyzeImage(
            ImageRequest(
                imagePath = "/tmp/image_without_extension",
                prompt = "what is shown?",
                maxTokens = 32,
            ),
        )

        assertEquals("IMAGE_VALIDATION_ERROR:UNSUPPORTED_EXTENSION:extension 'unknown' is not supported", output)
    }

    @Test
    fun `coerces negative max token values to zero`() {
        val output = module.analyzeImage(
            ImageRequest(
                imagePath = "photo.jpg",
                prompt = "describe",
                maxTokens = -10,
            ),
        )

        assertEquals(
            "IMAGE_ANALYSIS(v=1,extension=jpg,max_tokens=0): describe",
            output,
        )
    }

    @Test
    fun `truncates prompt summary to deterministic max length`() {
        val longPrompt = buildString {
            repeat(200) { append('a') }
        }

        val output = module.analyzeImage(
            ImageRequest(
                imagePath = "photo.png",
                prompt = longPrompt,
                maxTokens = 64,
            ),
        )

        val summary = output.substringAfter(": ")
        assertEquals(120, summary.length)
        assertTrue(summary.all { it == 'a' })
    }

    @Test
    fun `rejects blank image path`() {
        val output = module.analyzeImage(
            ImageRequest(
                imagePath = "   ",
                prompt = "describe",
                maxTokens = 16,
            ),
        )

        assertEquals("IMAGE_VALIDATION_ERROR:MISSING_IMAGE_PATH:image_path is required", output)
    }

    @Test
    fun `rejects blank prompt`() {
        val output = module.analyzeImage(
            ImageRequest(
                imagePath = "photo.jpg",
                prompt = "   \n\t ",
                maxTokens = 16,
            ),
        )

        assertEquals("IMAGE_VALIDATION_ERROR:MISSING_PROMPT:prompt is required", output)
    }

    @Test
    fun `accepts supported extensions including uppercase and pdf`() {
        val upperCaseOutput = module.analyzeImage(
            ImageRequest(
                imagePath = "photo.JPEG",
                prompt = "describe",
                maxTokens = 10,
            ),
        )
        val pdfOutput = module.analyzeImage(
            ImageRequest(
                imagePath = "/docs/scan.PDF",
                prompt = "summarize this document",
                maxTokens = 10,
            ),
        )

        assertEquals("IMAGE_ANALYSIS(v=1,extension=jpeg,max_tokens=10): describe", upperCaseOutput)
        assertEquals("IMAGE_ANALYSIS(v=1,extension=pdf,max_tokens=10): summarize this document", pdfOutput)
    }
}
