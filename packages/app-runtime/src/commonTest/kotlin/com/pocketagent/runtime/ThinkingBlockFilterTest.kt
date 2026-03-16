package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkingBlockFilterTest {

    @Test
    fun `stripThinkingBlocks removes single thinking block`() {
        val input = "<think>reasoning here</think>The answer is 42."
        assertEquals("The answer is 42.", ThinkingBlockFilter.stripThinkingBlocks(input))
    }

    @Test
    fun `stripThinkingBlocks removes multiple thinking blocks`() {
        val input = "<think>step 1</think>Hello <think>step 2</think>world"
        assertEquals("Hello world", ThinkingBlockFilter.stripThinkingBlocks(input))
    }

    @Test
    fun `stripThinkingBlocks keeps unclosed thinking tail visible`() {
        val input = "Before<think>unclosed reasoning"
        assertEquals("Before<think>unclosed reasoning", ThinkingBlockFilter.stripThinkingBlocks(input))
    }

    @Test
    fun `stripThinkingBlocks matches closing tags case-insensitively`() {
        val input = "<think>abc</THINK>Visible"
        assertEquals("Visible", ThinkingBlockFilter.stripThinkingBlocks(input))
    }

    @Test
    fun `stripThinkingBlocks trims whitespace after closed thinking block`() {
        val input = "<think>\n\nreasoning\n</think>\n\nVisible"
        assertEquals("Visible", ThinkingBlockFilter.stripThinkingBlocks(input))
    }

    @Test
    fun `streaming filter suppresses well formed think blocks and captures reasoning`() {
        val filter = ThinkingBlockFilter(captureReasoning = true)
        val output = buildString {
            append(filter.filterToken("Hello ").visibleText)
            append(filter.filterToken("<think>").visibleText)
            append(filter.filterToken("hidden").visibleText)
            append(filter.filterToken("</think>").visibleText)
            append(filter.filterToken("world").visibleText)
            append(filter.flush().visibleText)
        }

        assertEquals("Hello world", output)
        assertEquals("hidden", filter.capturedThinking())
    }

    @Test
    fun `streaming filter handles tags split across tokens`() {
        val filter = ThinkingBlockFilter(captureReasoning = true)
        val output = buildString {
            append(filter.filterToken("A").visibleText)
            append(filter.filterToken("<thi").visibleText)
            append(filter.filterToken("nk>").visibleText)
            append(filter.filterToken("hidden").visibleText)
            append(filter.filterToken("</thi").visibleText)
            append(filter.filterToken("nk>").visibleText)
            append(filter.filterToken("B").visibleText)
            append(filter.flush().visibleText)
        }

        assertEquals("AB", output)
        assertEquals("hidden", filter.capturedThinking())
    }

    @Test
    fun `streaming filter flushes natural angle bracket content without indefinite buffering`() {
        val filter = ThinkingBlockFilter(captureReasoning = false)
        val first = filter.filterToken("if x <").visibleText
        val second = filter.filterToken(" 5 then").visibleText
        val flush = filter.flush().visibleText

        assertEquals("if x ", first)
        assertEquals("< 5 then", second)
        assertEquals("", flush)
    }

    @Test
    fun `streaming filter matches tags case-insensitively`() {
        val filter = ThinkingBlockFilter(captureReasoning = true)
        val output = buildString {
            append(filter.filterToken("<THINK>").visibleText)
            append(filter.filterToken("reasoning").visibleText)
            append(filter.filterToken("</Think>").visibleText)
            append(filter.filterToken("Visible").visibleText)
            append(filter.flush().visibleText)
        }

        assertEquals("Visible", output)
        assertEquals("reasoning", filter.capturedThinking())
    }

    @Test
    fun `streaming filter keeps malformed unclosed block visible on flush`() {
        val filter = ThinkingBlockFilter(captureReasoning = true)
        val output = buildString {
            append(filter.filterToken("Answer ").visibleText)
            append(filter.filterToken("<think>").visibleText)
            append(filter.filterToken("draft").visibleText)
            append(filter.flush().visibleText)
        }

        assertEquals("Answer <think>draft", output)
        assertEquals("", filter.capturedThinking())
    }

    @Test
    fun `streaming filter tracks thinking state through tag lifecycle`() {
        val filter = ThinkingBlockFilter(captureReasoning = false)

        val opened = filter.filterToken("<think>")
        val inside = filter.filterToken("reasoning")
        val closed = filter.filterToken("</think>")

        assertTrue(opened.isCurrentlyThinking)
        assertTrue(inside.isCurrentlyThinking)
        assertFalse(closed.isCurrentlyThinking)
    }

    @Test
    fun `streaming filter normalizes whitespace after close`() {
        val filter = ThinkingBlockFilter(captureReasoning = false)
        val output = buildString {
            append(filter.filterToken("<think>").visibleText)
            append(filter.filterToken("reasoning").visibleText)
            append(filter.filterToken("</think>\n\n").visibleText)
            append(filter.filterToken("Visible").visibleText)
            append(filter.flush().visibleText)
        }

        assertEquals("Visible", output)
    }

    @Test
    fun `streaming filter captures multiple thinking blocks`() {
        val filter = ThinkingBlockFilter(captureReasoning = true)
        val output = buildString {
            append(filter.filterToken("<think>a</think>").visibleText)
            append(filter.filterToken("b").visibleText)
            append(filter.filterToken("<think>c</think>").visibleText)
            append(filter.filterToken("d").visibleText)
            append(filter.flush().visibleText)
        }

        assertEquals("bd", output)
        assertEquals("ac", filter.capturedThinking())
    }
}
