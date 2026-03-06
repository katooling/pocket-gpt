package com.pocketagent.runtime

/**
 * Canonical interaction schema shared across runtime components.
 * This mirrors OpenAI-style roles/content/tool-calls while remaining provider agnostic.
 */
data class InteractionMessage(
    val role: InteractionRole,
    val parts: List<InteractionContentPart>,
    val toolCalls: List<InteractionToolCall> = emptyList(),
    val toolCallId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

enum class InteractionRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

sealed interface InteractionContentPart {
    data class Text(val text: String) : InteractionContentPart
}

data class InteractionToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class RenderedPrompt(
    val prompt: String,
    val stopSequences: List<String>,
    val templateProfile: ModelTemplateProfile,
)

enum class ModelTemplateProfile {
    CHATML,
    LLAMA3,
}
