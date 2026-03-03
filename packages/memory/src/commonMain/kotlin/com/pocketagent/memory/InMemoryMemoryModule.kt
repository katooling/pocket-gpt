package com.pocketagent.memory

class InMemoryMemoryModule : MemoryModule {
    private val chunks: MutableList<MemoryChunk> = mutableListOf()

    override fun saveMemoryChunk(chunk: MemoryChunk): Boolean {
        chunks.add(chunk)
        return true
    }

    override fun retrieveRelevantMemory(query: String, limit: Int): List<MemoryChunk> {
        val queryTokens = tokenize(query)
        return chunks
            .map { chunk -> chunk to overlapScore(queryTokens, tokenize(chunk.content)) }
            .sortedByDescending { it.second }
            .filter { it.second > 0 }
            .take(limit.coerceAtLeast(0))
            .map { it.first }
    }

    override fun pruneMemory(maxChunks: Int): Int {
        if (maxChunks < 0) return 0
        if (chunks.size <= maxChunks) return 0
        val toRemove = chunks.size - maxChunks
        repeat(toRemove) {
            chunks.removeAt(0)
        }
        return toRemove
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun overlapScore(a: Set<String>, b: Set<String>): Int {
        return a.intersect(b).size
    }
}
