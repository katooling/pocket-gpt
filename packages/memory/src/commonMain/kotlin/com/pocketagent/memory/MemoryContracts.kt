package com.pocketagent.memory

data class MemoryChunk(
    val id: String,
    val content: String,
    val createdAtEpochMs: Long,
)

interface MemoryModule {
    fun saveMemoryChunk(chunk: MemoryChunk): Boolean
    fun retrieveRelevantMemory(query: String, limit: Int): List<MemoryChunk>
    fun pruneMemory(maxChunks: Int): Int
}
