package com.pocketagent.runtime

import java.io.File
import java.security.MessageDigest

/**
 * Provides save/load operations for session cache serialization.
 * Implemented by the active [RuntimeSessionCachePort].
 */
fun interface SessionCacheSerializer {
    fun saveSessionCache(filePath: String): Boolean
    fun loadSessionCache(filePath: String): Boolean = false
}

data class SessionCacheIdentity(
    val cacheKey: String,
    val modelId: String,
    val modelVersion: String? = null,
    val modelPathHash: String,
    val loadFingerprint: String,
) {
    fun serialize(): String {
        return buildString {
            append("schemaVersion=").append(SCHEMA_VERSION).append('\n')
            append("cacheKey=").append(cacheKey).append('\n')
            append("modelId=").append(modelId).append('\n')
            append("modelVersion=").append(modelVersion.orEmpty()).append('\n')
            append("modelPathHash=").append(modelPathHash).append('\n')
            append("loadFingerprint=").append(loadFingerprint).append('\n')
        }
    }

    companion object {
        private const val SCHEMA_VERSION = "1"

        fun deserialize(serialized: String): SessionCacheIdentity? {
            val entries = parseKeyValueLines(serialized)
            if (entries["schemaVersion"] != SCHEMA_VERSION) {
                return null
            }
            val cacheKey = entries["cacheKey"]?.takeIf { it.isNotBlank() } ?: return null
            val modelId = entries["modelId"]?.takeIf { it.isNotBlank() } ?: return null
            val modelPathHash = entries["modelPathHash"]?.takeIf { it.isNotBlank() } ?: return null
            val loadFingerprint = entries["loadFingerprint"]?.takeIf { it.isNotBlank() } ?: return null
            return SessionCacheIdentity(
                cacheKey = cacheKey,
                modelId = modelId,
                modelVersion = entries["modelVersion"]?.ifBlank { null },
                modelPathHash = modelPathHash,
                loadFingerprint = loadFingerprint,
            )
        }
    }
}

private const val SESSION_CACHE_METADATA_VERSION = "1"

private fun parseKeyValueLines(serialized: String): Map<String, String> {
    return serialized.lineSequence()
        .mapNotNull { line ->
            val normalized = line.trimEnd('\r')
            val separatorIndex = normalized.indexOf('=')
            if (separatorIndex <= 0) {
                null
            } else {
                normalized.substring(0, separatorIndex) to normalized.substring(separatorIndex + 1)
            }
        }
        .toMap()
}

private data class SessionCacheMetadata(
    val metadataVersion: String = SESSION_CACHE_METADATA_VERSION,
    val savedAtEpochMs: Long,
    val cacheBytes: Long,
    val identityHash: String,
) {
    fun serialize(): String {
        return buildString {
            append("metadataVersion=").append(metadataVersion).append('\n')
            append("savedAtEpochMs=").append(savedAtEpochMs).append('\n')
            append("cacheBytes=").append(cacheBytes).append('\n')
            append("identityHash=").append(identityHash).append('\n')
        }
    }

    companion object {
        fun parse(entries: Map<String, String>): SessionCacheMetadata? {
            val metadataVersion = entries["metadataVersion"]?.ifBlank { null } ?: return null
            if (metadataVersion != SESSION_CACHE_METADATA_VERSION) {
                return null
            }
            val savedAtEpochMs = entries["savedAtEpochMs"]?.toLongOrNull() ?: return null
            val cacheBytes = entries["cacheBytes"]?.toLongOrNull() ?: return null
            val identityHash = entries["identityHash"]?.takeIf { it.isNotBlank() } ?: return null
            return SessionCacheMetadata(
                metadataVersion = metadataVersion,
                savedAtEpochMs = savedAtEpochMs,
                cacheBytes = cacheBytes,
                identityHash = identityHash,
            )
        }
    }
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

    fun save(serializer: SessionCacheSerializer, identity: SessionCacheIdentity): Boolean {
        val file = cacheFileFor(identity.cacheKey)
        val metadataFile = metadataFileFor(file)
        val tempFile = File(cacheDir, "${file.name}.tmp")
        val tempMetadataFile = File(cacheDir, "${metadataFile.name}.tmp")
        return try {
            val saved = serializer.saveSessionCache(tempFile.absolutePath)
            if (!saved || !tempFile.exists()) {
                false
            } else {
                val serializedIdentity = identity.serialize()
                val metadata = SessionCacheMetadata(
                    savedAtEpochMs = System.currentTimeMillis(),
                    cacheBytes = tempFile.length(),
                    identityHash = sha256DigestHex(serializedIdentity),
                )
                tempMetadataFile.writeText(serializedIdentity)
                tempMetadataFile.appendText(metadata.serialize())
                if (file.exists() && !file.delete()) {
                    false
                } else if (metadataFile.exists() && !metadataFile.delete()) {
                    false
                } else {
                    val cacheRenamed = tempFile.renameTo(file)
                    val metadataRenamed = cacheRenamed && tempMetadataFile.renameTo(metadataFile)
                    if (!cacheRenamed || !metadataRenamed) {
                        if (cacheRenamed) {
                            file.delete()
                        }
                        false
                    } else {
                        file.setLastModified(metadata.savedAtEpochMs)
                        metadataFile.setLastModified(metadata.savedAtEpochMs)
                        enforceBudget(exemptFile = file)
                        true
                    }
                }
            }
        } catch (_: Exception) {
            false
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            if (tempMetadataFile.exists()) {
                tempMetadataFile.delete()
            }
        }
    }

    fun restore(serializer: SessionCacheSerializer, identity: SessionCacheIdentity): Boolean {
        val file = cacheFileFor(identity.cacheKey)
        val metadataFile = metadataFileFor(file)
        if (!file.exists()) {
            metadataFile.delete()
            return false
        }
        val cachedIdentity = readIdentity(metadataFile)
        if (cachedIdentity != identity) {
            deleteEntry(file)
            return false
        }
        val metadata = readMetadata(metadataFile)
        val identityHashMatches = metadata?.identityHash?.equals(sha256DigestHex(cachedIdentity.serialize())) ?: true
        if (!identityHashMatches) {
            deleteEntry(file)
            return false
        }
        if (file.length() > maxTotalSizeBytes.coerceAtLeast(1L)) {
            deleteEntry(file)
            return false
        }
        return try {
            val restored = serializer.loadSessionCache(file.absolutePath)
            if (!restored) {
                deleteEntry(file)
            } else {
                val now = System.currentTimeMillis()
                file.setLastModified(now)
                metadataFile.setLastModified(now)
            }
            restored
        } catch (_: Exception) {
            deleteEntry(file)
            false
        }
    }

    fun evict(cacheKey: String): Boolean {
        return deleteEntry(cacheFileFor(cacheKey))
    }

    fun evictAll() {
        cacheDir.listFiles()
            ?.filter {
                it.name.endsWith(SESSION_CACHE_EXTENSION) ||
                    it.name.endsWith("$SESSION_CACHE_EXTENSION$SESSION_CACHE_METADATA_EXTENSION")
            }
            ?.forEach { it.delete() }
    }

    fun hasCacheFor(identity: SessionCacheIdentity): Boolean {
        val file = cacheFileFor(identity.cacheKey)
        if (!file.exists()) {
            return false
        }
        return readIdentity(metadataFileFor(file)) == identity
    }

    fun cacheSizeMb(): Double {
        val totalBytes = sessionFiles().sumOf { it.length() }
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

    private fun metadataFileFor(cacheFile: File): File {
        return File(cacheDir, "${cacheFile.name}$SESSION_CACHE_METADATA_EXTENSION")
    }

    private fun sessionFiles(): List<File> {
        return cacheDir.listFiles()
            ?.filter { it.name.endsWith(SESSION_CACHE_EXTENSION) && it.isFile }
            .orEmpty()
    }

    private fun readIdentity(metadataFile: File): SessionCacheIdentity? {
        if (!metadataFile.exists() || !metadataFile.isFile) {
            return null
        }
        return runCatching {
            SessionCacheIdentity.deserialize(metadataFile.readText())
        }.getOrNull()
    }

    private fun readMetadata(metadataFile: File): SessionCacheMetadata? {
        if (!metadataFile.exists() || !metadataFile.isFile) {
            return null
        }
        return runCatching {
            SessionCacheMetadata.parse(parseKeyValueLines(metadataFile.readText()))
        }.getOrNull()
    }

    private fun deleteEntry(cacheFile: File): Boolean {
        val metadataFile = metadataFileFor(cacheFile)
        val cacheDeleted = !cacheFile.exists() || cacheFile.delete()
        val metadataDeleted = !metadataFile.exists() || metadataFile.delete()
        return cacheDeleted && metadataDeleted
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
                if (deleteEntry(file)) {
                    totalBytes = (totalBytes - deletedBytes).coerceAtLeast(0L)
                }
            }
    }

    private companion object {
        const val SESSION_CACHE_EXTENSION = ".session"
        const val SESSION_CACHE_METADATA_EXTENSION = ".meta"
        const val DEFAULT_MAX_TOTAL_SIZE_BYTES: Long = 128L * 1024L * 1024L
    }
}

private fun sha256DigestHex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

data class SessionCacheDiagnostics(
    val entryCount: Int,
    val totalBytes: Long,
    val maxTotalBytes: Long,
) {
    val totalMb: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    val maxMb: Double get() = maxTotalBytes.toDouble() / (1024.0 * 1024.0)
}
