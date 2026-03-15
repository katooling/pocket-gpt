package com.pocketagent.runtime

interface ChatTemplateRenderer {
    fun render(
        messages: List<InteractionMessage>,
        modelProfile: ModelTemplateProfile,
    ): RenderedPrompt
}

class DefaultChatTemplateRenderer : ChatTemplateRenderer {
    override fun render(
        messages: List<InteractionMessage>,
        modelProfile: ModelTemplateProfile,
    ): RenderedPrompt {
        return when (modelProfile) {
            ModelTemplateProfile.CHATML -> renderChatMl(messages)
            ModelTemplateProfile.LLAMA3 -> renderLlama3(messages)
            ModelTemplateProfile.PHI -> renderPhi(messages)
        }
    }

    private fun renderChatMl(messages: List<InteractionMessage>): RenderedPrompt {
        val prompt = buildString {
            messages.forEach { message ->
                append("<|im_start|>")
                append(message.role.toTemplateRole())
                append('\n')
                append(message.renderedText())
                append('\n')
                append("<|im_end|>")
                append('\n')
            }
            append("<|im_start|>assistant\n")
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = listOf("<|im_end|>", "<|im_start|>user"),
            templateProfile = ModelTemplateProfile.CHATML,
        )
    }

    private fun renderLlama3(messages: List<InteractionMessage>): RenderedPrompt {
        val prompt = buildString {
            append("<|begin_of_text|>")
            messages.forEach { message ->
                append("<|start_header_id|>")
                append(message.role.toTemplateRole())
                append("<|end_header_id|>\n\n")
                append(message.renderedText())
                append("<|eot_id|>")
            }
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = listOf("<|eot_id|>", "<|start_header_id|>user<|end_header_id|>"),
            templateProfile = ModelTemplateProfile.LLAMA3,
        )
    }
    private fun renderPhi(messages: List<InteractionMessage>): RenderedPrompt {
        val prompt = buildString {
            messages.forEach { message ->
                append("<|")
                append(message.role.toTemplateRole())
                append("|>")
                append(message.renderedText())
                append("<|end|>")
            }
            append("<|assistant|>")
        }
        return RenderedPrompt(
            prompt = prompt,
            stopSequences = listOf("<|end|>", "<|endoftext|>"),
            templateProfile = ModelTemplateProfile.PHI,
        )
    }
}

private fun InteractionRole.toTemplateRole(): String {
    return when (this) {
        InteractionRole.SYSTEM -> "system"
        InteractionRole.USER -> "user"
        InteractionRole.ASSISTANT -> "assistant"
        InteractionRole.TOOL -> "tool"
    }
}

private fun InteractionMessage.renderedText(): String {
    return parts.joinToString(separator = "\n") { part ->
        when (part) {
            is InteractionContentPart.Text -> part.text
        }
    }
}
