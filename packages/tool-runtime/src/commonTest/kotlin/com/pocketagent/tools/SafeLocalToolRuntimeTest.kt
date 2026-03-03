package com.pocketagent.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeLocalToolRuntimeTest {
    private val runtime = SafeLocalToolRuntime()

    @Test
    fun `calculator tool executes valid expression`() {
        val result = runtime.executeToolCall(ToolCall("calculator", "{\"expression\":\"4+5\"}"))
        assertTrue(result.success)
    }

    @Test
    fun `rejects dangerous payload fragments`() {
        val valid = runtime.validateToolCall(ToolCall("local_search", "{\"query\":\"hello\"}"))
        val invalid = runtime.validateToolCall(ToolCall("local_search", "{\"query\":\"exec rm -rf\"}"))
        assertTrue(valid)
        assertFalse(invalid)
    }
}
