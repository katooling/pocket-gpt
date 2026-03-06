package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTemplateRendererTest {
    private val renderer = DefaultChatTemplateRenderer()

    @Test
    fun `chatml profile renders role headers and stop sequences`() {
        val rendered = renderer.render(
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.SYSTEM,
                    parts = listOf(InteractionContentPart.Text("You are local.")),
                ),
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text("hello")),
                ),
            ),
            modelProfile = ModelTemplateProfile.CHATML,
        )

        assertTrue(rendered.prompt.contains("<|im_start|>system"))
        assertTrue(rendered.prompt.contains("<|im_start|>user"))
        assertTrue(rendered.prompt.endsWith("<|im_start|>assistant\n"))
        assertEquals(listOf("<|im_end|>", "<|im_start|>user"), rendered.stopSequences)
    }

    @Test
    fun `llama3 profile renders headers and stop sequences`() {
        val rendered = renderer.render(
            messages = listOf(
                InteractionMessage(
                    role = InteractionRole.USER,
                    parts = listOf(InteractionContentPart.Text("hello")),
                ),
            ),
            modelProfile = ModelTemplateProfile.LLAMA3,
        )

        assertTrue(rendered.prompt.contains("<|begin_of_text|>"))
        assertTrue(rendered.prompt.contains("<|start_header_id|>user<|end_header_id|>"))
        assertTrue(rendered.prompt.contains("<|start_header_id|>assistant<|end_header_id|>"))
        assertEquals(listOf("<|eot_id|>", "<|start_header_id|>user<|end_header_id|>"), rendered.stopSequences)
    }
}
