package com.pocketagent.runtime

class DiagnosticsRedactor(
    private val sensitiveKeys: Set<String> = DEFAULT_SENSITIVE_KEYS,
) {
    fun redact(raw: String): String {
        return raw.split("|").joinToString("|") { section ->
            section.split(";").joinToString(";") { entry ->
                val trimmed = entry.trim()
                val key = trimmed.substringBefore("=").substringBefore(":").trim().lowercase()
                if (sensitiveKeys.contains(key)) {
                    "${key}=[REDACTED]"
                } else {
                    entry
                }
            }
        }
    }

    companion object {
        private val DEFAULT_SENSITIVE_KEYS = setOf(
            "user",
            "assistant",
            "prompt",
            "memory",
            "content",
            "jsonargs",
            "tool_args",
        )
    }
}
