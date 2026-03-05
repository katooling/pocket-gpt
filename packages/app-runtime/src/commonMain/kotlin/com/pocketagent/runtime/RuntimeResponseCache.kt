package com.pocketagent.runtime

internal class RuntimeResponseCache(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val entries = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    fun enabled(): Boolean = maxEntries > 0 && ttlMs > 0

    fun get(key: String): String? {
        if (!enabled()) {
            return null
        }
        val entry = entries[key] ?: return null
        if (isExpired(entry)) {
            entries.remove(key)
            return null
        }
        return entry.text
    }

    fun put(key: String, text: String) {
        if (!enabled()) {
            return
        }
        entries[key] = CacheEntry(text = text, storedAtMs = nowMs())
    }

    fun clear() {
        entries.clear()
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        return nowMs() - entry.storedAtMs > ttlMs
    }

    private data class CacheEntry(
        val text: String,
        val storedAtMs: Long,
    )
}
