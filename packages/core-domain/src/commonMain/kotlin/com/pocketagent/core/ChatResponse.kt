package com.pocketagent.core

data class ChatResponse(
    val sessionId: SessionId,
    val modelId: String,
    val text: String,
    val firstTokenLatencyMs: Long,
    val totalLatencyMs: Long,
    val requestId: String = "",
    val finishReason: String = "completed",
    val runtimeStats: RuntimeExecutionStats? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
)

data class ChatToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)
