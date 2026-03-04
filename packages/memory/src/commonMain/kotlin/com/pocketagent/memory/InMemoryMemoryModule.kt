package com.pocketagent.memory

class InMemoryMemoryModule : MemoryModule {
    private val chunks: MutableList<MemoryChunk> = mutableListOf()

    override fun saveMemoryChunk(chunk: MemoryChunk): Boolean {
        chunks.add(chunk)
        return true
    }

    override fun retrieveRelevantMemory(query: String, limit: Int): List<MemoryChunk> {
        val queryTokens = MemoryRetrievalScorer.tokenize(query)
        return chunks
            .map { chunk ->
                chunk to MemoryRetrievalScorer.overlapScore(
                    queryTokens,
                    MemoryRetrievalScorer.tokenize(chunk.content),
                )
            }
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

}
