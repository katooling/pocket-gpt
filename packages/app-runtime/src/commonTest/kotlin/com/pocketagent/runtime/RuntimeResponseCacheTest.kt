package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeResponseCacheTest {
    @Test
    fun `disabled cache ignores reads and writes`() {
        val cache = RuntimeResponseCache(maxEntries = 0, ttlMs = 60_000)
        cache.put("k1", "value")
        assertNull(cache.get("k1"))
    }

    @Test
    fun `cache returns non-expired value`() {
        var now = 10_000L
        val cache = RuntimeResponseCache(maxEntries = 2, ttlMs = 30_000) { now }

        cache.put("k1", "value-1")
        now += 1000L

        assertEquals("value-1", cache.get("k1"))
    }

    @Test
    fun `cache expires stale value`() {
        var now = 1_000L
        val cache = RuntimeResponseCache(maxEntries = 2, ttlMs = 500L) { now }

        cache.put("k1", "value-1")
        now += 600L

        assertNull(cache.get("k1"))
    }

    @Test
    fun `cache evicts least recently used entry`() {
        var now = 1_000L
        val cache = RuntimeResponseCache(maxEntries = 2, ttlMs = 60_000L) { now }

        cache.put("k1", "value-1")
        now += 1L
        cache.put("k2", "value-2")
        now += 1L
        assertEquals("value-1", cache.get("k1"))
        now += 1L
        cache.put("k3", "value-3")

        assertEquals("value-1", cache.get("k1"))
        assertNull(cache.get("k2"))
        assertEquals("value-3", cache.get("k3"))
        assertTrue(cache.enabled())
    }
}
