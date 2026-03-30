package com.pocketagent.nativebridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KvCacheMethodPresetTest {
    @Test
    fun `all presets have unique codes`() {
        val codes = KvCacheMethodPreset.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `fromCode resolves ULTRA`() {
        assertEquals(KvCacheMethodPreset.ULTRA, KvCacheMethodPreset.fromCode(3))
    }

    @Test
    fun `fromCode resolves EXTREME`() {
        assertEquals(KvCacheMethodPreset.EXTREME, KvCacheMethodPreset.fromCode(4))
    }

    @Test
    fun `fromCode unknown falls back to SAFE`() {
        assertEquals(KvCacheMethodPreset.SAFE, KvCacheMethodPreset.fromCode(99))
    }

    @Test
    fun `preset ordering is monotonically increasing compression`() {
        val ordered = listOf(
            KvCacheMethodPreset.SAFE,
            KvCacheMethodPreset.BALANCED,
            KvCacheMethodPreset.AGGRESSIVE,
            KvCacheMethodPreset.ULTRA,
            KvCacheMethodPreset.EXTREME,
        )
        ordered.zipWithNext().forEach { (a, b) ->
            assert(a.code < b.code) { "${a.name} code ${a.code} should be < ${b.name} code ${b.code}" }
        }
    }
}
