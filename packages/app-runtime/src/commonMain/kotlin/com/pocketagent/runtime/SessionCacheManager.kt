package com.pocketagent.runtime

import java.io.File

/**
 * Provides save/load operations for session cache serialization.
 * Implemented by [com.pocketagent.nativebridge.LlamaCppInferenceModule].
 */
fun interface SessionCacheSerializer {
    fun saveSessionCache(filePath: String): Boolean
    fun loadSessionCache(filePath: String): Boolean = false
}

/**
 * Manages on-disk KV cache session files so that inference context can survive
 * model unloads and app restarts. Each model gets a single session file keyed
 * by model ID.
 *
 * Lifecycle:
 * 1. Before unloading a model → [save] writes the active prefix cache slot to disk
 * 2. After loading a model   → [restore] loads the cached state back into a prefix cache slot
 * 3. On model change / cleanup → [evict] deletes the file for a given model
 */
class SessionCacheManager(
    private val cacheDir: File,
    private val maxTotalSizeBytes: Long = DEFAULT_MAX_TOTAL_SIZE_BYTES,
) {
    init {
        cacheDir.mkdirs()
    }

    fun save(serializer: SessionCacheSerializer, cacheKey: String): Boolean {
        val file = cacheFileFor(cacheKey)
        val tempFile = File(cacheDir, "${file.name}.tmp")
        return try {
            val saved = serializer.saveSessionCache(tempFile.absolutePath)
            if (saved && tempFile.exists()) {
                val renamed = tempFile.renameTo(file)
                if (renamed) {
                    enforceBudget(exemptFile = file)
                }
                renamed
            } else {
                tempFile.delete()
                false
            }
        } catch (_: Exception) {
            tempFile.delete()
            false
        }
    }

    fun restore(serializer: SessionCacheSerializer, cacheKey: String): Boolean {
        val file = cacheFileFor(cacheKey)
        if (!file.exists()) return false
        if (file.length() > maxTotalSizeBytes.coerceAtLeast(1L)) {
            file.delete()
            return false
        }
        return try {
            val restored = serializer.loadSessionCache(file.absolutePath)
            if (!restored) {
                file.delete()
            } else {
                file.setLastModified(System.currentTimeMillis())
            }
            restored
        } catch (_: Exception) {
            file.delete()
            false
        }
    }

    fun evict(cacheKey: String): Boolean {
        val file = cacheFileFor(cacheKey)
        return file.delete()
    }

    fun evictAll() {
        cacheDir.listFiles()
            ?.filter { it.name.endsWith(SESSION_CACHE_EXTENSION) }
            ?.forEach { it.delete() }
    }

    fun hasCacheFor(cacheKey: String): Boolean {
        return cacheFileFor(cacheKey).exists()
    }

    fun cacheSizeMb(): Double {
        val totalBytes = cacheDir.listFiles()
            ?.filter { it.name.endsWith(SESSION_CACHE_EXTENSION) }
            ?.sumOf { it.length() }
            ?: 0L
        return totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    fun diagnostics(): SessionCacheDiagnostics {
        val files = sessionFiles()
        val totalBytes = files.sumOf { it.length() }
        return SessionCacheDiagnostics(
            entryCount = files.size,
            totalBytes = totalBytes,
            maxTotalBytes = maxTotalSizeBytes,
        )
    }

    private fun cacheFileFor(cacheKey: String): File {
        val safeName = cacheKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(cacheDir, "$safeName$SESSION_CACHE_EXTENSION")
    }

    private fun sessionFiles(): List<File> {
        return cacheDir.listFiles()
            ?.filter { it.name.endsWith(SESSION_CACHE_EXTENSION) && it.isFile }
            .orEmpty()
    }

    private fun enforceBudget(exemptFile: File? = null) {
        var totalBytes = sessionFiles().sumOf { it.length() }
        if (totalBytes <= maxTotalSizeBytes) {
            return
        }
        sessionFiles()
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (exemptFile != null && file.absolutePath == exemptFile.absolutePath) {
                    return@forEach
                }
                if (totalBytes <= maxTotalSizeBytes) {
                    return
                }
                val deletedBytes = file.length()
                if (file.delete()) {
                    totalBytes = (totalBytes - deletedBytes).coerceAtLeast(0L)
                }
            }
    }

    private companion object {
        const val SESSION_CACHE_EXTENSION = ".session"
        const val DEFAULT_MAX_TOTAL_SIZE_BYTES: Long = 128L * 1024L * 1024L
    }
}

data class SessionCacheDiagnostics(
    val entryCount: Int,
    val totalBytes: Long,
    val maxTotalBytes: Long,
) {
    val totalMb: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    val maxMb: Double get() = maxTotalBytes.toDouble() / (1024.0 * 1024.0)
}
