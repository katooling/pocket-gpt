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
) {
    init {
        cacheDir.mkdirs()
    }

    fun save(serializer: SessionCacheSerializer, modelId: String): Boolean {
        val file = cacheFileFor(modelId)
        val tempFile = File(cacheDir, "${file.name}.tmp")
        return try {
            val saved = serializer.saveSessionCache(tempFile.absolutePath)
            if (saved && tempFile.exists()) {
                tempFile.renameTo(file)
            } else {
                tempFile.delete()
                false
            }
        } catch (_: Exception) {
            tempFile.delete()
            false
        }
    }

    fun restore(serializer: SessionCacheSerializer, modelId: String): Boolean {
        val file = cacheFileFor(modelId)
        if (!file.exists()) return false
        return try {
            serializer.loadSessionCache(file.absolutePath)
        } catch (_: Exception) {
            false
        }
    }

    fun evict(modelId: String): Boolean {
        val file = cacheFileFor(modelId)
        return file.delete()
    }

    fun evictAll() {
        cacheDir.listFiles()
            ?.filter { it.name.endsWith(SESSION_CACHE_EXTENSION) }
            ?.forEach { it.delete() }
    }

    fun hasCacheFor(modelId: String): Boolean {
        return cacheFileFor(modelId).exists()
    }

    fun cacheSizeMb(): Double {
        val totalBytes = cacheDir.listFiles()
            ?.filter { it.name.endsWith(SESSION_CACHE_EXTENSION) }
            ?.sumOf { it.length() }
            ?: 0L
        return totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    private fun cacheFileFor(modelId: String): File {
        val safeName = modelId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(cacheDir, "$safeName$SESSION_CACHE_EXTENSION")
    }

    private companion object {
        const val SESSION_CACHE_EXTENSION = ".session"
    }
}
