package com.pocketagent.runtime

/**
 * Filters `<think>...</think>` blocks from model output.
 *
 * SmolLM3 (and other models with extended thinking) emit reasoning traces
 * wrapped in `<think>` / `</think>` tags. This filter:
 * - Strips thinking blocks from streamed tokens so they are not shown to the user.
 * - Provides [stripThinkingBlocks] for post-processing the final response text.
 *
 * A single instance should be used per generation request and is **not** thread-safe.
 */
internal class ThinkingBlockFilter(
    private val enabled: Boolean = true,
) {
    private var insideThinkBlock = false
    private val pendingBuffer = StringBuilder()

    /**
     * Accepts a token from the stream and returns the text that should be
     * forwarded to the UI.  Returns an empty string when the token is part
     * of a thinking block (or part of a partial tag match that hasn't resolved yet).
     */
    fun filterToken(token: String): String {
        if (!enabled) return token

        pendingBuffer.append(token)
        val result = StringBuilder()
        drainBuffer(result)
        return result.toString()
    }

    /**
     * Call after generation completes to flush any remaining buffered text
     * that was held for partial-tag matching.
     */
    fun flush(): String {
        if (!enabled) return ""
        val result = StringBuilder()
        if (!insideThinkBlock && pendingBuffer.isNotEmpty()) {
            result.append(pendingBuffer)
            pendingBuffer.clear()
        } else {
            pendingBuffer.clear()
        }
        return result.toString()
    }

    private fun drainBuffer(output: StringBuilder) {
        while (pendingBuffer.isNotEmpty()) {
            val buf = pendingBuffer.toString()
            if (insideThinkBlock) {
                val closeIdx = buf.indexOf(CLOSE_TAG)
                if (closeIdx >= 0) {
                    pendingBuffer.delete(0, closeIdx + CLOSE_TAG.length)
                    insideThinkBlock = false
                    continue
                }
                if (couldBePartialTag(buf, CLOSE_TAG)) {
                    return
                }
                pendingBuffer.clear()
                return
            }

            val openIdx = buf.indexOf(OPEN_TAG)
            if (openIdx >= 0) {
                if (openIdx > 0) {
                    output.append(buf, 0, openIdx)
                }
                pendingBuffer.delete(0, openIdx + OPEN_TAG.length)
                insideThinkBlock = true
                continue
            }
            if (couldBePartialTag(buf, OPEN_TAG)) {
                val safeLength = buf.length - (OPEN_TAG.length - 1)
                if (safeLength > 0) {
                    output.append(buf, 0, safeLength)
                    pendingBuffer.delete(0, safeLength)
                }
                return
            }
            output.append(buf)
            pendingBuffer.clear()
            return
        }
    }

    companion object {
        internal const val OPEN_TAG = "<think>"
        internal const val CLOSE_TAG = "</think>"

        private fun couldBePartialTag(buffer: String, tag: String): Boolean {
            val maxOverlap = minOf(buffer.length, tag.length - 1)
            for (overlapLen in maxOverlap downTo 1) {
                val suffix = buffer.substring(buffer.length - overlapLen)
                if (tag.startsWith(suffix)) {
                    return true
                }
            }
            return false
        }

        /**
         * Strips all `<think>...</think>` blocks from the given text.
         * Used for post-processing the final response before storage and display.
         */
        fun stripThinkingBlocks(text: String): String {
            if (!text.contains(OPEN_TAG)) return text
            val result = StringBuilder(text.length)
            var pos = 0
            while (pos < text.length) {
                val openIdx = text.indexOf(OPEN_TAG, pos)
                if (openIdx < 0) {
                    result.append(text, pos, text.length)
                    break
                }
                result.append(text, pos, openIdx)
                val closeIdx = text.indexOf(CLOSE_TAG, openIdx + OPEN_TAG.length)
                pos = if (closeIdx >= 0) {
                    closeIdx + CLOSE_TAG.length
                } else {
                    break
                }
            }
            return result.toString().trim()
        }
    }
}
