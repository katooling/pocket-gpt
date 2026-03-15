package com.pocketagent.runtime

internal object ResponsePipelineFactory {
    data class StreamFilters(
        val visibleThinkingFilter: ThinkingBlockFilter?,
        val reasoningCaptureFilter: ThinkingBlockFilter?,
    )

    fun createStreamFilters(
        profile: ModelInteractionProfile,
        showThinking: Boolean,
    ): StreamFilters {
        return when (profile.thinkingSupport) {
            ThinkingSupport.NONE -> StreamFilters(
                visibleThinkingFilter = null,
                reasoningCaptureFilter = null,
            )
            ThinkingSupport.THINK_TAGS -> StreamFilters(
                visibleThinkingFilter = ThinkingBlockFilter(enabled = true),
                reasoningCaptureFilter = ThinkingBlockFilter(enabled = showThinking),
            )
        }
    }

    fun parseToolCalls(
        text: String,
        profile: ModelInteractionProfile,
    ): ToolCallParser.ParsedToolCalls {
        return when (val toolCallSupport = profile.toolCallSupport) {
            ToolCallSupport.NONE -> ToolCallParser.ParsedToolCalls(
                textWithoutToolCalls = text,
                toolCalls = emptyList(),
            )
            is ToolCallSupport.XmlTagFormat -> ToolCallParser.parse(
                text = text,
                openTag = toolCallSupport.openTag,
                closeTag = toolCallSupport.closeTag,
            )
        }
    }

    fun stripThinking(
        text: String,
        profile: ModelInteractionProfile,
    ): String {
        return when (profile.thinkingSupport) {
            ThinkingSupport.NONE -> text
            ThinkingSupport.THINK_TAGS -> ThinkingBlockFilter.stripThinkingBlocks(text)
        }
    }
}
