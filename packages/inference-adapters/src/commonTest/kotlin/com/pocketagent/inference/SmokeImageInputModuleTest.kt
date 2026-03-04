package com.pocketagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeImageInputModuleTest {
    private val module = SmokeImageInputModule()

    @Test
    fun `normalizes extension and prompt whitespace`() {
        val output = module.analyzeImage(
            ImageRequest(
                imagePath = "/tmp/scan.DOCX",
                prompt = "   summarize\nthis\tpage   quickly   ",
                maxTokens = 128,
            ),
        )

        assertEquals(
            "IMAGE_ANALYSIS(v=1,extension=docx,max_tokens=128): summarize this page quickly",
            output,
        )
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

        assertEquals(
            "IMAGE_ANALYSIS(v=1,extension=unknown,max_tokens=32): what is shown?",
            output,
        )
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
}
