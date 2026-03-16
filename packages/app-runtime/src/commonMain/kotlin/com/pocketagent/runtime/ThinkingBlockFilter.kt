package com.pocketagent.runtime

/**
 * Filters `<think>...</think>` blocks from model output while optionally capturing
 * the reasoning text from well-formed blocks.
 *
 * A single instance should be used per generation request and is not thread-safe.
 */
internal class ThinkingBlockFilter(
    private val captureReasoning: Boolean,
    private val openTag: String = DEFAULT_OPEN_TAG,
    private val closeTag: String = DEFAULT_CLOSE_TAG,
) {
    data class FilterResult(
        val visibleText: String = "",
        val reasoningDelta: String = "",
        val isCurrentlyThinking: Boolean = false,
    )

    private enum class ParserState {
        LOOKING_FOR_OPENING,
        EATING_WHITESPACE_AFTER_OPEN,
        INSIDE_THINKING,
        EATING_WHITESPACE_AFTER_CLOSE,
    }

    private val pendingBuffer = StringBuilder()
    private val confirmedReasoningBuffer = StringBuilder()
    private val currentReasoningBuffer = StringBuilder()
    private val currentRawThinkingBlock = StringBuilder()
    private var state: ParserState = ParserState.LOOKING_FOR_OPENING
    private var tokenSequence: Int = 0
    private var partialCandidateStartedAtToken: Int? = null

    val isCurrentlyThinking: Boolean
        get() = state == ParserState.EATING_WHITESPACE_AFTER_OPEN || state == ParserState.INSIDE_THINKING

    fun filterToken(token: String): FilterResult {
        if (token.isEmpty()) {
            return FilterResult(isCurrentlyThinking = isCurrentlyThinking)
        }
        tokenSequence += 1
        pendingBuffer.append(token)
        return drainBuffer()
    }

    fun flush(): FilterResult {
        val visible = StringBuilder()
        when (state) {
            ParserState.LOOKING_FOR_OPENING,
            ParserState.EATING_WHITESPACE_AFTER_CLOSE,
            -> {
                if (pendingBuffer.isNotEmpty()) {
                    visible.append(pendingBuffer)
                }
            }

            ParserState.EATING_WHITESPACE_AFTER_OPEN,
            ParserState.INSIDE_THINKING,
            -> {
                // The block never closed, so treat it as normal visible output.
                visible.append(currentRawThinkingBlock)
                visible.append(pendingBuffer)
            }
        }
        resetCurrentBlock()
        pendingBuffer.clear()
        partialCandidateStartedAtToken = null
        state = ParserState.LOOKING_FOR_OPENING
        return FilterResult(
            visibleText = visible.toString(),
            isCurrentlyThinking = false,
        )
    }

    fun capturedThinking(): String = confirmedReasoningBuffer.toString().trim()

    private fun drainBuffer(): FilterResult {
        val visible = StringBuilder()
        val reasoningDelta = StringBuilder()

        while (pendingBuffer.isNotEmpty()) {
            when (state) {
                ParserState.LOOKING_FOR_OPENING -> {
                    val buffer = pendingBuffer.toString()
                    val openIdx = buffer.indexOfIgnoreCase(openTag)
                    if (openIdx >= 0) {
                        if (openIdx > 0) {
                            visible.append(buffer, 0, openIdx)
                        }
                        currentRawThinkingBlock.clear()
                        currentRawThinkingBlock.append(buffer, openIdx, openIdx + openTag.length)
                        pendingBuffer.delete(0, openIdx + openTag.length)
                        partialCandidateStartedAtToken = null
                        state = ParserState.EATING_WHITESPACE_AFTER_OPEN
                        continue
                    }

                    val overlap = buffer.partialTagOverlap(openTag)
                    if (overlap > 0) {
                        if (buffer.length > overlap) {
                            visible.append(buffer, 0, buffer.length - overlap)
                            pendingBuffer.delete(0, buffer.length - overlap)
                        }
                        if (shouldForcePartialFlush()) {
                            visible.append(pendingBuffer)
                            pendingBuffer.clear()
                            partialCandidateStartedAtToken = null
                        }
                        break
                    }

                    visible.append(buffer)
                    pendingBuffer.clear()
                    partialCandidateStartedAtToken = null
                }

                ParserState.EATING_WHITESPACE_AFTER_OPEN -> {
                    val skipped = pendingBuffer.consumeLeadingWhitespace()
                    if (skipped.isNotEmpty()) {
                        currentRawThinkingBlock.append(skipped)
                    }
                    partialCandidateStartedAtToken = null
                    if (pendingBuffer.isEmpty()) {
                        break
                    }
                    state = ParserState.INSIDE_THINKING
                }

                ParserState.INSIDE_THINKING -> {
                    val buffer = pendingBuffer.toString()
                    val closeIdx = buffer.indexOfIgnoreCase(closeTag)
                    if (closeIdx >= 0) {
                        if (closeIdx > 0) {
                            val reasoningChunk = buffer.substring(0, closeIdx)
                            currentReasoningBuffer.append(reasoningChunk)
                            currentRawThinkingBlock.append(reasoningChunk)
                        }
                        pendingBuffer.delete(0, closeIdx + closeTag.length)
                        val confirmedReasoning = currentReasoningBuffer.toString()
                        if (captureReasoning && confirmedReasoning.isNotBlank()) {
                            confirmedReasoningBuffer.append(confirmedReasoning)
                            reasoningDelta.append(confirmedReasoning)
                        }
                        resetCurrentBlock()
                        partialCandidateStartedAtToken = null
                        state = ParserState.EATING_WHITESPACE_AFTER_CLOSE
                        continue
                    }

                    val overlap = buffer.partialTagOverlap(closeTag)
                    if (overlap > 0) {
                        if (buffer.length > overlap) {
                            val safeChunk = buffer.substring(0, buffer.length - overlap)
                            currentReasoningBuffer.append(safeChunk)
                            currentRawThinkingBlock.append(safeChunk)
                            pendingBuffer.delete(0, buffer.length - overlap)
                        }
                        if (shouldForcePartialFlush()) {
                            currentReasoningBuffer.append(pendingBuffer)
                            currentRawThinkingBlock.append(pendingBuffer)
                            pendingBuffer.clear()
                            partialCandidateStartedAtToken = null
                        }
                        break
                    }

                    currentReasoningBuffer.append(buffer)
                    currentRawThinkingBlock.append(buffer)
                    pendingBuffer.clear()
                    partialCandidateStartedAtToken = null
                }

                ParserState.EATING_WHITESPACE_AFTER_CLOSE -> {
                    pendingBuffer.consumeLeadingWhitespace()
                    partialCandidateStartedAtToken = null
                    if (pendingBuffer.isEmpty()) {
                        break
                    }
                    state = ParserState.LOOKING_FOR_OPENING
                }
            }
        }

        return FilterResult(
            visibleText = visible.toString(),
            reasoningDelta = reasoningDelta.toString(),
            isCurrentlyThinking = isCurrentlyThinking,
        )
    }

    private fun shouldForcePartialFlush(): Boolean {
        val start = partialCandidateStartedAtToken
        if (start == null) {
            partialCandidateStartedAtToken = tokenSequence
            return false
        }
        return (tokenSequence - start + 1) > maxOf(openTag.length, closeTag.length) + 1
    }

    private fun resetCurrentBlock() {
        currentReasoningBuffer.clear()
        currentRawThinkingBlock.clear()
    }

    companion object {
        internal const val DEFAULT_OPEN_TAG = "<think>"
        internal const val DEFAULT_CLOSE_TAG = "</think>"

        fun stripThinkingBlocks(
            text: String,
            openTag: String = DEFAULT_OPEN_TAG,
            closeTag: String = DEFAULT_CLOSE_TAG,
        ): String {
            if (text.isBlank()) {
                return text
            }
            val result = StringBuilder(text.length)
            var cursor = 0
            while (cursor < text.length) {
                val openIdx = text.indexOfIgnoreCase(openTag, cursor)
                if (openIdx < 0) {
                    result.append(text, cursor, text.length)
                    break
                }
                result.append(text, cursor, openIdx)
                val closeIdx = text.indexOfIgnoreCase(closeTag, openIdx + openTag.length)
                if (closeIdx < 0) {
                    result.append(text, openIdx, text.length)
                    break
                }
                cursor = closeIdx + closeTag.length
                while (cursor < text.length && text[cursor].isWhitespace()) {
                    cursor += 1
                }
            }
            return result.toString().trim()
        }
    }
}

private fun String.indexOfIgnoreCase(
    other: String,
    startIndex: Int = 0,
): Int {
    if (other.isEmpty()) {
        return startIndex.coerceIn(0, length)
    }
    if (startIndex >= length) {
        return -1
    }
    val lastStart = length - other.length
    for (index in startIndex.coerceAtLeast(0)..lastStart) {
        if (regionMatches(index, other, 0, other.length, ignoreCase = true)) {
            return index
        }
    }
    return -1
}

private fun String.partialTagOverlap(tag: String): Int {
    val maxOverlap = minOf(length, tag.length - 1)
    for (overlap in maxOverlap downTo 1) {
        if (tag.regionMatches(0, this, length - overlap, overlap, ignoreCase = true)) {
            return overlap
        }
    }
    return 0
}

private fun StringBuilder.consumeLeadingWhitespace(): String {
    var consumed = 0
    while (consumed < length && this[consumed].isWhitespace()) {
        consumed += 1
    }
    if (consumed == 0) {
        return ""
    }
    val prefix = substring(0, consumed)
    delete(0, consumed)
    return prefix
}
