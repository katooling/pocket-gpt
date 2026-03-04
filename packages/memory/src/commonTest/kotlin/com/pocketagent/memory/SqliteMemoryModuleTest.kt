package com.pocketagent.memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteMemoryModuleTest {
    @Test
    fun `saves and retrieves relevant memories by overlap`() {
        val module = newModule()

        assertTrue(module.saveMemoryChunk(MemoryChunk("1", "launch meeting checklist", 1)))
        assertTrue(module.saveMemoryChunk(MemoryChunk("2", "grocery list milk tea", 2)))

        val result = module.retrieveRelevantMemory("launch status meeting", limit = 2)

        assertEquals(1, result.size)
        assertEquals("1", result.first().id)
    }

    @Test
    fun `persists chunks across module re-instantiation`() {
        val dbPath = Files.createTempDirectory("memory-sqlite-persist").resolve("memory.db")
        val first = SqliteMemoryModule.fromFile(dbPath)
        assertTrue(first.saveMemoryChunk(MemoryChunk("persist-1", "state survives restart", 100)))

        val second = SqliteMemoryModule.fromFile(dbPath)
        val result = second.retrieveRelevantMemory("survives restart", limit = 3)

        assertEquals(1, result.size)
        assertEquals("persist-1", result.first().id)
    }

    @Test
    fun `prune removes oldest chunks and returns removed count`() {
        val module = newModule()
        module.saveMemoryChunk(MemoryChunk("1", "alpha notes", 1))
        module.saveMemoryChunk(MemoryChunk("2", "beta notes", 2))
        module.saveMemoryChunk(MemoryChunk("3", "gamma notes", 3))

        val removed = module.pruneMemory(maxChunks = 2)
        val remaining = module.retrieveRelevantMemory("alpha beta gamma", limit = 10).map { it.id }.sorted()

        assertEquals(1, removed)
        assertEquals(listOf("2", "3"), remaining)
    }

    @Test
    fun `prune is no-op when max is negative or already under cap`() {
        val module = newModule()
        module.saveMemoryChunk(MemoryChunk("1", "one", 1))
        module.saveMemoryChunk(MemoryChunk("2", "two", 2))

        assertEquals(0, module.pruneMemory(maxChunks = -1))
        assertEquals(0, module.pruneMemory(maxChunks = 5))
    }

    @Test
    fun `save rejects blank ids or content`() {
        val module = newModule()

        assertFalse(module.saveMemoryChunk(MemoryChunk("", "value", 1)))
        assertFalse(module.saveMemoryChunk(MemoryChunk("id", "   ", 1)))
    }

    @Test
    fun `retrieve returns empty when query has no tokens or limit is non-positive`() {
        val module = newModule()
        module.saveMemoryChunk(MemoryChunk("1", "launch plan", 1))

        assertEquals(emptyList(), module.retrieveRelevantMemory("...", limit = 5))
        assertEquals(emptyList(), module.retrieveRelevantMemory("launch", limit = 0))
        assertEquals(emptyList(), module.retrieveRelevantMemory("launch", limit = -1))
    }

    private fun newModule(): SqliteMemoryModule {
        val dbPath = Files.createTempDirectory("memory-sqlite-test").resolve("memory.db")
        return SqliteMemoryModule.fromFile(dbPath)
    }
}
