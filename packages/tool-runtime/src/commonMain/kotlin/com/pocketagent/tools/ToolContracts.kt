package com.pocketagent.tools

data class ToolCall(
    val name: String,
    val jsonArgs: String,
)

data class ToolResult(
    val success: Boolean,
    val content: String,
)

interface ToolModule {
    fun listEnabledTools(): List<String>
    fun validateToolCall(call: ToolCall): Boolean
    fun executeToolCall(call: ToolCall): ToolResult
}
