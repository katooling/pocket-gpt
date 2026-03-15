package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `stripThinkingBlocks passes through text without thinking blocks`() {
        val input = "No thinking here."
        assertEquals("No thinking here.", ThinkingBlockFilter.stripThinkingBlocks(input))
    }

    @Test
    fun `stripThinkingBlocks handles unclosed thinking block by discarding tail`() {
        val input = "Before<think>unclosed reasoning"
        assertEquals("Before", ThinkingBlockFilter.stripThinkingBlocks(input))
    }

    @Test
    fun `stripThinkingBlocks handles empty thinking block`() {
        val input = "<think></think>Result"
        assertEquals("Result", ThinkingBlockFilter.stripThinkingBlocks(input))
    }

    @Test
    fun `streaming filter suppresses tokens inside think block`() {
        val filter = ThinkingBlockFilter()

        assertEquals("", filter.filterToken("<think>"))
        assertEquals("", filter.filterToken("reasoning"))
        assertEquals("", filter.filterToken("</think>"))
        assertEquals("Visible", filter.filterToken("Visible"))
    }

    @Test
    fun `streaming filter emits text before and after think block`() {
        val filter = ThinkingBlockFilter()
        val output = StringBuilder()

        output.append(filter.filterToken("Hello "))
        output.append(filter.filterToken("<think>"))
        output.append(filter.filterToken("hidden"))
        output.append(filter.filterToken("</think>"))
        output.append(filter.filterToken("world"))
        output.append(filter.flush())

        assertEquals("Hello world", output.toString())
    }

    @Test
    fun `streaming filter handles tag split across tokens`() {
        val filter = ThinkingBlockFilter()
        val output = StringBuilder()

        output.append(filter.filterToken("A"))
        output.append(filter.filterToken("<thi"))
        output.append(filter.filterToken("nk>"))
        output.append(filter.filterToken("hidden"))
        output.append(filter.filterToken("</thi"))
        output.append(filter.filterToken("nk>"))
        output.append(filter.filterToken("B"))
        output.append(filter.flush())

        assertEquals("AB", output.toString())
    }

    @Test
    fun `streaming filter is pass-through when disabled`() {
        val filter = ThinkingBlockFilter(enabled = false)

        assertEquals("<think>", filter.filterToken("<think>"))
        assertEquals("visible", filter.filterToken("visible"))
        assertEquals("</think>", filter.filterToken("</think>"))
        assertEquals("", filter.flush())
    }

    @Test
    fun `flush discards buffered content inside open think block`() {
        val filter = ThinkingBlockFilter()

        filter.filterToken("<think>")
        filter.filterToken("unfinished reasoning")
        val flushed = filter.flush()

        assertEquals("", flushed)
    }

    @Test
    fun `flush emits buffered content outside think block`() {
        val filter = ThinkingBlockFilter()
        val output = StringBuilder()

        output.append(filter.filterToken("partial"))
        output.append(filter.filterToken("<thi"))
        // Buffer holds "<thi" which could be partial tag
        output.append(filter.flush())

        // The partial "<thi" was not a real tag, so it should be flushed as visible text
        assertEquals("partial<thi", output.toString())
    }
}
