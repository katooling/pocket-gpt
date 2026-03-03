package com.pocketagent.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryMemoryModuleTest {
    private val memory = InMemoryMemoryModule()

    @Test
    fun `retrieves relevant memories by overlap`() {
        memory.saveMemoryChunk(MemoryChunk("1", "meeting notes about launch metrics", 1))
        memory.saveMemoryChunk(MemoryChunk("2", "shopping list apples and tea", 2))
        val result = memory.retrieveRelevantMemory("launch metrics status", limit = 2)
        assertTrue(result.isNotEmpty())
        assertEquals("1", result.first().id)
    }
}
