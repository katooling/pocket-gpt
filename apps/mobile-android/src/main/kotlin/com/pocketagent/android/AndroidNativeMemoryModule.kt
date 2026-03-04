package com.pocketagent.android

import com.pocketagent.memory.MemoryChunk
import com.pocketagent.memory.MemoryModule
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class AndroidNativeMemoryModule private constructor(
    private val storagePath: Path,
) : MemoryModule {
    private val chunksById: MutableMap<String, MemoryChunk> = linkedMapOf()

    init {
        storagePath.parent?.let { Files.createDirectories(it) }
        loadFromDisk()
    }

    override fun saveMemoryChunk(chunk: MemoryChunk): Boolean {
        if (chunk.id.isBlank() || chunk.content.isBlank()) {
            return false
        }
        chunksById[chunk.id] = chunk
        persist()
        return true
    }

    override fun retrieveRelevantMemory(query: String, limit: Int): List<MemoryChunk> {
        if (limit <= 0) {
            return emptyList()
        }
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) {
            return emptyList()
        }
        return chunksById.values
            .map { chunk -> chunk to overlapScore(queryTokens, tokenize(chunk.content)) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryChunk, Int>> { it.second }
                    .thenByDescending { it.first.createdAtEpochMs }
                    .thenBy { it.first.id },
            )
            .take(limit)
            .map { it.first }
    }

    override fun pruneMemory(maxChunks: Int): Int {
        if (maxChunks < 0) {
            return 0
        }
        val ordered = chunksById.values.sortedWith(compareBy<MemoryChunk> { it.createdAtEpochMs }.thenBy { it.id })
        val toRemove = (ordered.size - maxChunks).coerceAtLeast(0)
        if (toRemove <= 0) {
            return 0
        }
        ordered.take(toRemove).forEach { chunk ->
            chunksById.remove(chunk.id)
        }
        persist()
        return toRemove
    }

    private fun loadFromDisk() {
        if (!Files.exists(storagePath)) {
            return
        }
        val lines = Files.readAllLines(storagePath)
        lines.forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3) {
                return@forEach
            }
            val id = parts[0]
            val createdAt = parts[1].toLongOrNull() ?: return@forEach
            val content = runCatching {
                String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
            }.getOrNull() ?: return@forEach
            chunksById[id] = MemoryChunk(
                id = id,
                content = content,
                createdAtEpochMs = createdAt,
            )
        }
    }

    private fun persist() {
        val ordered = chunksById.values.sortedWith(compareBy<MemoryChunk> { it.createdAtEpochMs }.thenBy { it.id })
        val lines = ordered.map { chunk ->
            val encodedContent = Base64.getEncoder().encodeToString(chunk.content.toByteArray(Charsets.UTF_8))
            "${chunk.id}\t${chunk.createdAtEpochMs}\t$encodedContent"
        }
        Files.write(storagePath, lines)
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

    companion object {
        fun fromPath(path: Path): AndroidNativeMemoryModule {
            return AndroidNativeMemoryModule(path.toAbsolutePath().normalize())
        }

        fun ephemeralRuntimeModule(): AndroidNativeMemoryModule {
            val dbPath = Path.of(
                System.getProperty("java.io.tmpdir"),
                "pocketagent-memory",
                "android-runtime-memory-${System.nanoTime()}.db",
            )
            return fromPath(dbPath)
        }

        fun defaultRuntimeModule(): AndroidNativeMemoryModule {
            val dbPath = Path.of(
                System.getProperty("java.io.tmpdir"),
                "pocketagent-memory",
                "android-runtime-memory.db",
            )
            return fromPath(dbPath)
        }
    }
}
