package com.pocketagent.runtime

/**
 * User-specified sampling parameter overrides. Null fields use the performance profile defaults.
 * Mapped from the Android-layer CompletionSettings at the ChatSendFlow boundary.
 */
data class SamplingOverrides(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val repeatPenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val systemPrompt: String? = null,
)
