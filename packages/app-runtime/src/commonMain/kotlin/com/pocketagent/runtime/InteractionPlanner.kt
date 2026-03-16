package com.pocketagent.runtime

import com.pocketagent.core.Turn
import com.pocketagent.inference.DeviceState
import kotlin.jvm.JvmName

class InteractionPlanner(
    private val interactionRegistry: ModelInteractionRegistry = ModelInteractionRegistry(),
    private val templateRenderer: ChatTemplateRenderer = DefaultChatTemplateRenderer(),
    private val enabledToolNames: List<String> = emptyList(),
) {
    fun buildRenderedPrompt(
        modelId: String,
        messages: List<InteractionMessage>,
        memorySnippets: List<String>,
        taskType: String,
        deviceState: DeviceState,
        promptCharBudget: Int,
        showThinking: Boolean? = null,
    ): RenderedPrompt {
        val interactionProfile = interactionRegistry.interactionProfileForModel(modelId)
        val templateProfile = interactionProfile.templateProfile
        val enrichedMessages = mutableListOf<InteractionMessage>()
        val systemText = buildString {
            append("task=$taskType battery=${deviceState.batteryPercent} thermal=${deviceState.thermalLevel} ram_gb=${deviceState.ramClassGb}")
            if (interactionProfile.thinkingSupport != ThinkingSupport.NONE) {
                showThinking?.let { enabled ->
                    append('\n')
                    append(if (enabled) "/think" else "/no_think")
                }
            }
            if (enabledToolNames.isNotEmpty() && interactionProfile.toolCallSupport != ToolCallSupport.NONE) {
                when (val toolSupport = interactionProfile.toolCallSupport) {
                    ToolCallSupport.NONE -> Unit
                    is ToolCallSupport.XmlTagFormat -> {
                        append(
                            ToolCallParser.renderToolDefinitionsXml(
                                toolNames = enabledToolNames,
                                openTag = toolSupport.openTag,
                                closeTag = toolSupport.closeTag,
                            ),
                        )
                    }
                }
            }
        }
        enrichedMessages += InteractionMessage(
            id = "system-task-${System.currentTimeMillis()}",
            role = InteractionRole.SYSTEM,
            parts = listOf(InteractionContentPart.Text(systemText)),
        )
        if (memorySnippets.isNotEmpty()) {
            enrichedMessages += InteractionMessage(
                id = "system-memory-${System.currentTimeMillis()}",
                role = InteractionRole.SYSTEM,
                parts = listOf(
                    InteractionContentPart.Text(
                        memorySnippets.joinToString(separator = "\n") { snippet -> "memory: $snippet" },
                    ),
                ),
            )
        }
        val promptTokenBudget = (promptCharBudget.coerceAtLeast(MIN_PROMPT_CHARS) / APPROX_CHARS_PER_TOKEN)
            .coerceAtLeast(MIN_PROMPT_TOKENS)
        enrichedMessages += pruneForBudget(
            messages = messages
                .takeLast(MAX_CONTEXT_MESSAGES)
                .map { message -> message.removeThinkingFromContext(interactionProfile.thinkingSupport) },
            promptTokenBudget = promptTokenBudget,
        )
        return templateRenderer.render(messages = enrichedMessages, modelProfile = templateProfile)
    }

    @JvmName("buildRenderedPromptFromTurns")
    fun buildRenderedPrompt(
        modelId: String,
        turns: List<Turn>,
        memorySnippets: List<String>,
        taskType: String,
        deviceState: DeviceState,
        promptCharBudget: Int,
        showThinking: Boolean? = null,
    ): RenderedPrompt {
        return buildRenderedPrompt(
            modelId = modelId,
            messages = turns.map { turn -> turn.toInteractionMessage() },
            memorySnippets = memorySnippets,
            taskType = taskType,
            deviceState = deviceState,
            promptCharBudget = promptCharBudget,
            showThinking = showThinking,
        )
    }

    fun ensureTemplateAvailable(modelId: String): String? = interactionRegistry.ensureTemplateAvailable(modelId)

    fun interactionProfileForModel(modelId: String): ModelInteractionProfile {
        return interactionRegistry.interactionProfileForModel(modelId)
    }

    private fun pruneForBudget(
        messages: List<InteractionMessage>,
        promptTokenBudget: Int,
    ): List<InteractionMessage> {
        if (messages.isEmpty()) {
            return messages
        }

        val mustKeep = linkedSetOf<Int>()
        val latestUserIndex = messages.indexOfLast { message -> message.role == InteractionRole.USER }
        if (latestUserIndex >= 0) {
            mustKeep += latestUserIndex
            val previousAssistant = (latestUserIndex - 1 downTo 0)
                .firstOrNull { index -> messages[index].role == InteractionRole.ASSISTANT }
            if (previousAssistant != null) {
                mustKeep += previousAssistant
            }
            val nextAssistant = ((latestUserIndex + 1) until messages.size)
                .firstOrNull { index -> messages[index].role == InteractionRole.ASSISTANT }
            if (nextAssistant != null) {
                mustKeep += nextAssistant
            }
        }

        val latestAssistantToolCallIndex = messages.indexOfLast { message ->
            message.role == InteractionRole.ASSISTANT && message.toolCalls.isNotEmpty()
        }
        if (latestAssistantToolCallIndex >= 0) {
            mustKeep += latestAssistantToolCallIndex
            val activeToolCallIds = messages[latestAssistantToolCallIndex].toolCalls
                .map { toolCall -> toolCall.id }
                .toSet()
            messages.forEachIndexed { index, message ->
                if (message.role == InteractionRole.TOOL &&
                    message.toolCallId != null &&
                    activeToolCallIds.contains(message.toolCallId)
                ) {
                    mustKeep += index
                }
            }
        }

        messages.forEachIndexed { index, message ->
            if (message.role == InteractionRole.SYSTEM) {
                mustKeep += index
            }
        }

        val selected = linkedSetOf<Int>()
        mustKeep.sorted().forEach { index -> selected += index }
        for (index in messages.indices.reversed()) {
            if (selected.contains(index)) {
                continue
            }
            selected += index
            if (selected.estimatedTokenCount(messages) > promptTokenBudget) {
                selected.remove(index)
            }
        }

        while (selected.estimatedTokenCount(messages) > promptTokenBudget) {
            val removableSystemIndex = selected
                .sorted()
                .firstOrNull { index ->
                    messages[index].role == InteractionRole.SYSTEM && !mustKeep.contains(index)
                }
                ?: break
            selected.remove(removableSystemIndex)
        }

        while (selected.estimatedTokenCount(messages) > promptTokenBudget) {
            val removableOldest = selected.sorted().firstOrNull { index -> !mustKeep.contains(index) } ?: break
            selected.remove(removableOldest)
        }

        val finalIndexes = if (selected.estimatedTokenCount(messages) > promptTokenBudget && mustKeep.isNotEmpty()) {
            mustKeep
        } else {
            selected
        }
        return finalIndexes.sorted().map { index -> messages[index] }
    }

    private companion object {
        const val MAX_CONTEXT_MESSAGES: Int = 24
        const val APPROX_CHARS_PER_TOKEN: Int = 4
        const val MIN_PROMPT_CHARS: Int = 128
        const val MIN_PROMPT_TOKENS: Int = 64
    }
}

private fun InteractionMessage.removeThinkingFromContext(thinkingSupport: ThinkingSupport): InteractionMessage {
    if (role != InteractionRole.ASSISTANT || thinkingSupport == ThinkingSupport.NONE) {
        return this
    }
    return copy(
        parts = parts.map { part ->
            when (part) {
                is InteractionContentPart.Text -> part.copy(
                    text = ThinkingBlockFilter.stripThinkingBlocks(part.text),
                )
            }
        },
    )
}

private fun Turn.toInteractionMessage(): InteractionMessage {
    val interactionRole = when (role.trim().lowercase()) {
        "system" -> InteractionRole.SYSTEM
        "assistant" -> InteractionRole.ASSISTANT
        "tool" -> InteractionRole.TOOL
        else -> InteractionRole.USER
    }
    return InteractionMessage(
        id = "turn-${timestampEpochMs}-${interactionRole.name.lowercase()}",
        role = interactionRole,
        parts = listOf(InteractionContentPart.Text(content)),
        metadata = mapOf("timestampEpochMs" to timestampEpochMs.toString()),
    )
}

private fun Set<Int>.estimatedTokenCount(messages: List<InteractionMessage>): Int {
    if (isEmpty()) {
        return 0
    }
    return sumOf { index -> messages[index].estimatedTokenCount() }
}

private fun InteractionMessage.estimatedTokenCount(): Int {
    val contentChars = parts.sumOf { part ->
        when (part) {
            is InteractionContentPart.Text -> part.text.length
        }
    }
    val toolChars = toolCalls.sumOf { call ->
        call.id.length + call.name.length + call.argumentsJson.length + 12
    }
    val totalChars = (contentChars + toolChars).coerceAtLeast(1)
    return (totalChars / 4).coerceAtLeast(1)
}
