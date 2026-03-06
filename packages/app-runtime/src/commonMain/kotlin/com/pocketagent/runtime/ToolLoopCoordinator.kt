package com.pocketagent.runtime

import com.pocketagent.tools.ToolCall
import com.pocketagent.tools.ToolModule

class ToolLoopCoordinator(
    private val toolModule: ToolModule,
) {
    fun executeToolCall(toolName: String, jsonArgs: String): InteractionToolExecutionResult {
        val result = toolModule.executeToolCall(ToolCall(toolName, jsonArgs))
        return InteractionToolExecutionResult(
            success = result.success,
            content = result.content,
            message = InteractionMessage(
                role = InteractionRole.TOOL,
                toolCallId = "tool-${System.currentTimeMillis()}",
                parts = listOf(InteractionContentPart.Text(result.content)),
                metadata = mapOf("toolName" to toolName),
            ),
        )
    }
}

data class InteractionToolExecutionResult(
    val success: Boolean,
    val content: String,
    val message: InteractionMessage,
)
