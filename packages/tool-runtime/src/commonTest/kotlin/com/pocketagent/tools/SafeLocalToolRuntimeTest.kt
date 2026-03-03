package com.pocketagent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeLocalToolRuntimeTest {
    private val runtime = SafeLocalToolRuntime()

    @Test
    fun `calculator tool executes valid expression`() {
        val result = runtime.executeToolCall(ToolCall("calculator", "{\"expression\":\"4+5\"}"))
        assertTrue(result.success)
        assertEquals("9.0", result.content)
    }

    @Test
    fun `rejects malformed json with deterministic contract`() {
        val result = runtime.executeToolCall(ToolCall("calculator", "{\"expression\":"))
        assertValidationError(
            result = result,
            expectedCode = "INVALID_JSON",
            expectedDetail = "Payload must be valid JSON object text.",
        )
    }

    @Test
    fun `rejects schema-invalid payload type deterministically`() {
        val result = runtime.executeToolCall(ToolCall("calculator", "{\"expression\":123}"))
        assertValidationError(
            result = result,
            expectedCode = "INVALID_FIELD_TYPE",
            expectedDetail = "Field 'expression' must be a string.",
        )
    }

    @Test
    fun `rejects unknown field bypass attempts`() {
        val result = runtime.executeToolCall(
            ToolCall(
                "local_search",
                "{\"query\":\"hello\",\"tool\":\"calculator\"}",
            ),
        )
        assertValidationError(
            result = result,
            expectedCode = "UNKNOWN_FIELD",
            expectedDetail = "Unknown field 'tool'.",
        )
    }

    @Test
    fun `rejects missing required fields`() {
        val result = runtime.executeToolCall(ToolCall("local_search", "{}"))
        assertValidationError(
            result = result,
            expectedCode = "MISSING_REQUIRED_FIELD",
            expectedDetail = "Missing required field 'query'.",
        )
    }

    @Test
    fun `non allowlisted tool cannot execute`() {
        val result = runtime.executeToolCall(ToolCall("shell_exec", "{\"command\":\"ls\"}"))
        assertValidationError(
            result = result,
            expectedCode = "NOT_ALLOWLISTED",
            expectedDetail = "Tool 'shell_exec' is not allowlisted.",
        )
        assertFalse(runtime.validateToolCall(ToolCall("shell_exec", "{\"command\":\"ls\"}")))
    }

    @Test
    fun `rejects adversarial fragments and escaped bypass payloads`() {
        val plain = runtime.executeToolCall(ToolCall("local_search", "{\"query\":\"exec rm -rf\"}"))
        val escaped = runtime.executeToolCall(
            ToolCall("local_search", "{\"query\":\"\\u0065\\u0078\\u0065\\u0063 whoami\"}"),
        )

        assertValidationError(
            result = plain,
            expectedCode = "INVALID_FIELD_VALUE",
            expectedDetail = "Field 'query' contains denied fragment 'exec'.",
        )
        assertValidationError(
            result = escaped,
            expectedCode = "INVALID_FIELD_VALUE",
            expectedDetail = "Field 'query' contains denied fragment 'exec'.",
        )
    }

    @Test
    fun `rejects nested-object bypass for string field`() {
        val result = runtime.executeToolCall(
            ToolCall("local_search", "{\"query\":{\"operator\":\"equals\",\"value\":\"hello\"}}"),
        )
        assertValidationError(
            result = result,
            expectedCode = "INVALID_FIELD_TYPE",
            expectedDetail = "Field 'query' must be a string.",
        )
    }

    @Test
    fun `validate tool call is true for schema-valid payload`() {
        assertTrue(runtime.validateToolCall(ToolCall("local_search", "{\"query\":\"hello\"}")))
    }

    @Test
    fun `date_time rejects extra schema fields`() {
        val result = runtime.executeToolCall(ToolCall("date_time", "{\"timezone\":\"UTC\"}"))
        assertValidationError(
            result = result,
            expectedCode = "UNKNOWN_FIELD",
            expectedDetail = "Unknown field 'timezone'.",
        )
    }

    private fun assertValidationError(
        result: ToolResult,
        expectedCode: String,
        expectedDetail: String,
    ) {
        assertFalse(result.success)
        assertEquals(
            "TOOL_VALIDATION_ERROR:$expectedCode:$expectedDetail",
            result.content,
        )
    }
}
