package com.pocketagent.memory

internal object MemoryRetrievalScorer {
    fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun overlapScore(a: Set<String>, b: Set<String>): Int {
        return a.intersect(b).size
    }
}
