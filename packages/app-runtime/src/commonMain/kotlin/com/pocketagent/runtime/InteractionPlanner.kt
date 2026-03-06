package com.pocketagent.runtime

import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState

class InteractionPlanner(
    private val templateRegistry: ModelTemplateRegistry = ModelTemplateRegistry(),
    private val templateRenderer: ChatTemplateRenderer = DefaultChatTemplateRenderer(),
) {
    fun buildRenderedPrompt(
        modelId: String,
        turns: List<Turn>,
        memorySnippets: List<String>,
        taskType: String,
        deviceState: DeviceState,
        promptCharBudget: Int,
    ): RenderedPrompt {
        val profile = templateRegistry.templateProfileForModel(modelId)
        val messages = mutableListOf<InteractionMessage>()
        messages += InteractionMessage(
            role = InteractionRole.SYSTEM,
            parts = listOf(
                InteractionContentPart.Text(
                    "task=$taskType battery=${deviceState.batteryPercent} thermal=${deviceState.thermalLevel} ram_gb=${deviceState.ramClassGb}",
                ),
            ),
        )
        if (memorySnippets.isNotEmpty()) {
            messages += InteractionMessage(
                role = InteractionRole.SYSTEM,
                parts = listOf(
                    InteractionContentPart.Text(
                        memorySnippets.joinToString(separator = "\n") { "memory: $it" },
                    ),
                ),
            )
        }
        messages += turns.takeLast(MAX_CONTEXT_TURNS).map { turn -> turn.toInteractionMessage() }
        val rendered = templateRenderer.render(messages = messages, modelProfile = profile)
        return rendered.copy(prompt = rendered.prompt.take(promptCharBudget.coerceAtLeast(1)))
    }

    fun ensureTemplateAvailable(modelId: String): String? = templateRegistry.ensureTemplateAvailable(modelId)

    private companion object {
        const val MAX_CONTEXT_TURNS: Int = 12
    }
}

private fun Turn.toInteractionMessage(): InteractionMessage {
    val role = when (role.trim().lowercase()) {
        "system" -> InteractionRole.SYSTEM
        "assistant" -> InteractionRole.ASSISTANT
        "tool" -> InteractionRole.TOOL
        else -> InteractionRole.USER
    }
    return InteractionMessage(
        role = role,
        parts = listOf(InteractionContentPart.Text(content)),
        metadata = mapOf("timestampEpochMs" to timestampEpochMs.toString()),
    )
}
