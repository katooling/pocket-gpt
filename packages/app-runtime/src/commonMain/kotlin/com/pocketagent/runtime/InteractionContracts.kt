package com.pocketagent.runtime

/**
 * Canonical interaction schema shared across runtime components.
 * This mirrors OpenAI-style roles/content/tool-calls while remaining provider agnostic.
 */
data class InteractionMessage(
    val id: String = defaultInteractionMessageId(),
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
    val status: InteractionToolCallStatus = InteractionToolCallStatus.PENDING,
)

enum class InteractionToolCallStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

private fun defaultInteractionMessageId(): String {
    return "msg-${System.currentTimeMillis()}-${kotlin.random.Random.nextInt(10_000, 99_999)}"
}

data class RenderedPrompt(
    val prompt: String,
    val stopSequences: List<String>,
    val templateProfile: ModelTemplateProfile,
)

enum class ModelTemplateProfile {
    CHATML,
    LLAMA3,
}
