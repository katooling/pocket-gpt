package com.pocketagent.runtime

data class PrefixCacheDiagnostics(
    val hitRate: Double? = null,
    val lastCacheHit: Boolean? = null,
    val lastReusedTokens: Int? = null,
    val storeStateSuccessCount: Int? = null,
    val storeStateFailureCount: Int? = null,
    val restoreStateSuccessCount: Int? = null,
    val restoreStateFailureCount: Int? = null,
)

fun parsePrefixCacheDiagnostics(line: String?): PrefixCacheDiagnostics? {
    val fields = parsePipeDiagnosticsFields(line = line, prefix = "PREFIX_CACHE_DIAG|")
    if (fields.isEmpty()) {
        return null
    }
    return PrefixCacheDiagnostics(
        hitRate = fields["prefix_cache_hit_rate"]?.toDoubleOrNull(),
        lastCacheHit = fields["last_cache_hit"]?.toBooleanPipeValue(),
        lastReusedTokens = fields["last_reused_tokens"]?.toIntOrNull(),
        storeStateSuccessCount = fields["store_state_success"]?.toIntOrNull(),
        storeStateFailureCount = fields["store_state_failure"]?.toIntOrNull(),
        restoreStateSuccessCount = fields["restore_state_success"]?.toIntOrNull(),
        restoreStateFailureCount = fields["restore_state_failure"]?.toIntOrNull(),
    )
}

fun parsePipeDiagnosticsFields(line: String?, prefix: String): Map<String, String> {
    val normalizedLine = line?.trim()?.takeIf { value -> value.startsWith(prefix) } ?: return emptyMap()
    return normalizedLine.removePrefix(prefix)
        .split('|')
        .mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                token.substring(0, separator).trim() to token.substring(separator + 1).trim()
            }
        }
        .filter { (key, _) -> key.isNotEmpty() }
        .toMap()
}

private fun String.toBooleanPipeValue(): Boolean? {
    return when (trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }
}
