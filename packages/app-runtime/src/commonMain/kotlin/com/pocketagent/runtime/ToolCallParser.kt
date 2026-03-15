package com.pocketagent.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses `<tool_call>` blocks from model output text.
 *
 * SmolLM3 emits tool calls in the format:
 * ```
 * <tool_call>
 * {"name": "calculator", "arguments": {"expression": "4+5"}}
 * </tool_call>
 * ```
 *
 * This parser extracts all such blocks and maps them to [InteractionToolCall] instances.
 */
internal object ToolCallParser {

    private const val OPEN_TAG = "<tool_call>"
    private const val CLOSE_TAG = "</tool_call>"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    data class ParsedToolCalls(
        val toolCalls: List<InteractionToolCall>,
        val textWithoutToolCalls: String,
    )

    /**
     * Extracts all `<tool_call>...</tool_call>` blocks from [text] and returns
     * the parsed tool calls plus the remaining visible text.
     */
    fun parse(text: String): ParsedToolCalls {
        if (!text.contains(OPEN_TAG)) {
            return ParsedToolCalls(toolCalls = emptyList(), textWithoutToolCalls = text)
        }

        val toolCalls = mutableListOf<InteractionToolCall>()
        val remaining = StringBuilder(text.length)
        var pos = 0

        while (pos < text.length) {
            val openIdx = text.indexOf(OPEN_TAG, pos)
            if (openIdx < 0) {
                remaining.append(text, pos, text.length)
                break
            }
            remaining.append(text, pos, openIdx)
            val contentStart = openIdx + OPEN_TAG.length
            val closeIdx = text.indexOf(CLOSE_TAG, contentStart)
            if (closeIdx < 0) {
                // Unclosed tag — treat remaining as text
                remaining.append(text, openIdx, text.length)
                break
            }
            val jsonBlock = text.substring(contentStart, closeIdx).trim()
            val parsed = parseToolCallJson(jsonBlock)
            if (parsed != null) {
                toolCalls += parsed
            }
            pos = closeIdx + CLOSE_TAG.length
        }

        return ParsedToolCalls(
            toolCalls = toolCalls,
            textWithoutToolCalls = remaining.toString().trim(),
        )
    }

    private fun parseToolCallJson(jsonText: String): InteractionToolCall? {
        return runCatching {
            val obj = json.parseToJsonElement(jsonText).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return null
            val arguments = obj["arguments"]
            val argumentsJson = when (arguments) {
                is JsonObject -> arguments.toString()
                else -> arguments?.jsonPrimitive?.content ?: "{}"
            }
            InteractionToolCall(
                id = "tc-${System.currentTimeMillis()}-${name.hashCode().toUInt()}",
                name = name,
                argumentsJson = argumentsJson,
            )
        }.getOrNull()
    }

    /**
     * Renders tool definitions as XML for injection into the system prompt.
     * This format is compatible with SmolLM3's tool calling convention.
     */
    fun renderToolDefinitionsXml(toolNames: List<String>): String {
        if (toolNames.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("You have access to the following tools:")
            appendLine("<tools>")
            toolNames.forEach { name ->
                val schema = TOOL_SCHEMAS[name]
                if (schema != null) {
                    appendLine(schema)
                }
            }
            appendLine("</tools>")
            appendLine()
            append("When you need to use a tool, output a <tool_call> block with JSON containing \"name\" and \"arguments\" keys.")
        }
    }

    private val TOOL_SCHEMAS = mapOf(
        "calculator" to """<tool>
{"name": "calculator", "description": "Evaluate a simple arithmetic expression.", "parameters": {"expression": {"type": "string", "required": true, "description": "Arithmetic expression (e.g. '4+5')"}}}
</tool>""",
        "date_time" to """<tool>
{"name": "date_time", "description": "Get the current date and time.", "parameters": {}}
</tool>""",
        "notes_lookup" to """<tool>
{"name": "notes_lookup", "description": "Search local notes by query.", "parameters": {"query": {"type": "string", "required": true, "description": "Search query"}}}
</tool>""",
        "local_search" to """<tool>
{"name": "local_search", "description": "Search local documents.", "parameters": {"query": {"type": "string", "required": true, "description": "Search query"}}}
</tool>""",
        "reminder_create" to """<tool>
{"name": "reminder_create", "description": "Create a new reminder.", "parameters": {"title": {"type": "string", "required": true, "description": "Reminder title"}}}
</tool>""",
    )
}
