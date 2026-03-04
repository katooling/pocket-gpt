package com.pocketagent.memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileBackedMemoryModuleTest {
    @Test
    fun `save retrieve and prune preserve memory semantics`() {
        val path = Files.createTempDirectory("file-backed-memory").resolve("memory.db")
        val module = FileBackedMemoryModule.fromPath(path)

        assertTrue(module.saveMemoryChunk(MemoryChunk("1", "launch runtime checklist", 1)))
        assertTrue(module.saveMemoryChunk(MemoryChunk("2", "grocery list tea", 2)))

        val query = module.retrieveRelevantMemory("runtime launch", limit = 3)
        assertEquals(listOf("1"), query.map { it.id })

        assertTrue(module.saveMemoryChunk(MemoryChunk("3", "runtime security policy", 3)))
        assertEquals(1, module.pruneMemory(maxChunks = 2))
        val remaining = module.retrieveRelevantMemory("runtime", limit = 10).map { it.id }.sorted()
        assertEquals(listOf("3"), remaining)
    }

    @Test
    fun `module persists chunks across re-instantiation`() {
        val path = Files.createTempDirectory("file-backed-memory-persist").resolve("memory.db")
        val first = FileBackedMemoryModule.fromPath(path)
        assertTrue(first.saveMemoryChunk(MemoryChunk("persist-1", "state survives restart", 100)))

        val second = FileBackedMemoryModule.fromPath(path)
        val result = second.retrieveRelevantMemory("survives restart", limit = 3)
        assertEquals(1, result.size)
        assertEquals("persist-1", result.first().id)
    }

    @Test
    fun `save rejects blank ids or content`() {
        val path = Files.createTempDirectory("file-backed-memory-blank").resolve("memory.db")
        val module = FileBackedMemoryModule.fromPath(path)

        assertFalse(module.saveMemoryChunk(MemoryChunk("", "value", 1)))
        assertFalse(module.saveMemoryChunk(MemoryChunk("id", "   ", 1)))
    }
}
