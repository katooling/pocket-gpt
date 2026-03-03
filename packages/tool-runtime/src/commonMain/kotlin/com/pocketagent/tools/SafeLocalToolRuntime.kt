package com.pocketagent.tools

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

class SafeLocalToolRuntime : ToolModule {
    private val allowlistedTools = setOf(
        "calculator",
        "date_time",
        "notes_lookup",
        "local_search",
        "reminder_create",
    )

    override fun listEnabledTools(): List<String> = allowlistedTools.sorted()

    override fun validateToolCall(call: ToolCall): Boolean {
        if (!allowlistedTools.contains(call.name)) return false
        val trimmed = call.jsonArgs.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        // Block suspicious payloads for schema-safe local execution.
        val deniedFragments = listOf("exec", "bash", "sh -c", "http://", "https://")
        return deniedFragments.none { fragment -> trimmed.contains(fragment, ignoreCase = true) }
    }

    override fun executeToolCall(call: ToolCall): ToolResult {
        if (!validateToolCall(call)) {
            return ToolResult(false, "Rejected tool call: validation failed.")
        }
        val args = parseFlatJsonObject(call.jsonArgs)
        return when (call.name) {
            "calculator" -> runCalculator(args["expression"].orEmpty())
            "date_time" -> currentDateTime()
            "notes_lookup" -> ToolResult(true, "notes_lookup placeholder: no note store connected yet.")
            "local_search" -> ToolResult(true, "local_search placeholder for query='${args["query"].orEmpty()}'.")
            "reminder_create" -> ToolResult(true, "reminder_create accepted for '${args["title"].orEmpty()}'.")
            else -> ToolResult(false, "Unknown tool.")
        }
    }

    private fun runCalculator(expression: String): ToolResult {
        val simple = expression.replace(" ", "")
        val operator = listOf("+", "-", "*", "/").firstOrNull { simple.contains(it) }
            ?: return ToolResult(false, "Unsupported expression.")
        val parts = simple.split(operator)
        if (parts.size != 2) return ToolResult(false, "Unsupported expression.")
        val left = parts[0].toDoubleOrNull() ?: return ToolResult(false, "Invalid left operand.")
        val right = parts[1].toDoubleOrNull() ?: return ToolResult(false, "Invalid right operand.")
        val value = when (operator) {
            "+" -> left + right
            "-" -> left - right
            "*" -> left * right
            "/" -> if (right == 0.0) return ToolResult(false, "Division by zero.") else left / right
            else -> return ToolResult(false, "Unsupported operator.")
        }
        val rounded = (value * 10000.0).roundToLong() / 10000.0
        return ToolResult(true, rounded.toString())
    }

    private fun currentDateTime(): ToolResult {
        val now = Instant.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return ToolResult(true, formatter.format(now))
    }

    private fun parseFlatJsonObject(json: String): Map<String, String> {
        val body = json.trim().removePrefix("{").removeSuffix("}")
        if (body.isBlank()) return emptyMap()
        return body.split(",").mapNotNull { pair ->
            val split = pair.split(":", limit = 2)
            if (split.size != 2) return@mapNotNull null
            val key = split[0].trim().removePrefix("\"").removeSuffix("\"")
            val value = split[1].trim().removePrefix("\"").removeSuffix("\"")
            key to value
        }.toMap()
    }
}
