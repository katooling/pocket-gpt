package com.pocketagent.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCacheManagerTest {
    @Test
    fun `save writes metadata sidecar and restore succeeds for matching identity`() {
        val cacheDir = createCacheDir()
        try {
            val manager = SessionCacheManager(cacheDir = cacheDir, maxTotalSizeBytes = 1_024L * 1_024L)
            val serializer = RecordingSessionCacheSerializer()
            val identity = sessionCacheIdentity()

            assertTrue(manager.save(serializer = serializer, identity = identity))
            assertTrue(File(cacheDir, "${identity.cacheKey}.session").exists())
            assertTrue(File(cacheDir, "${identity.cacheKey}.session.meta").exists())
            assertTrue(manager.hasCacheFor(identity))
            assertTrue(manager.restore(serializer = serializer, identity = identity))
            assertEquals(1, serializer.loadCalls)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `save includes extended metadata fields for diagnostics`() {
        val cacheDir = createCacheDir()
        try {
            val manager = SessionCacheManager(cacheDir = cacheDir, maxTotalSizeBytes = 1_024L * 1_024L)
            val serializer = RecordingSessionCacheSerializer()
            val identity = sessionCacheIdentity()

            assertTrue(manager.save(serializer = serializer, identity = identity))
            val metadataFile = File(cacheDir, "${identity.cacheKey}.session.meta")
            val entries = parseMetadataEntries(metadataFile.readText())

            assertEquals("1", entries["metadataVersion"])
            assertTrue(entries["savedAtEpochMs"]?.toLongOrNull() ?: 0L > 0L)
            assertEquals(File(cacheDir, "${identity.cacheKey}.session").length(), entries["cacheBytes"]?.toLongOrNull())
            assertEquals(sha256Hex(identity.serialize()), entries["identityHash"])
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `restore rejects cache when metadata sidecar is missing`() {
        val cacheDir = createCacheDir()
        try {
            val manager = SessionCacheManager(cacheDir = cacheDir, maxTotalSizeBytes = 1_024L * 1_024L)
            val serializer = RecordingSessionCacheSerializer()
            val identity = sessionCacheIdentity()

            assertTrue(manager.save(serializer = serializer, identity = identity))
            assertTrue(File(cacheDir, "${identity.cacheKey}.session.meta").delete())

            assertFalse(manager.hasCacheFor(identity))
            assertFalse(manager.restore(serializer = serializer, identity = identity))
            assertFalse(File(cacheDir, "${identity.cacheKey}.session").exists())
            assertEquals(0, serializer.loadCalls)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `restore rejects cache when metadata identity hash mismatches request`() {
        val cacheDir = createCacheDir()
        try {
            val manager = SessionCacheManager(cacheDir = cacheDir, maxTotalSizeBytes = 1_024L * 1_024L)
            val serializer = RecordingSessionCacheSerializer()
            val identity = sessionCacheIdentity()

            assertTrue(manager.save(serializer = serializer, identity = identity))

            val metadataFile = File(cacheDir, "${identity.cacheKey}.session.meta")
            val corrupted = metadataFile.readText().lineSequence()
                .map { line ->
                    if (line.startsWith("identityHash=")) {
                        "identityHash=invalid-hash"
                    } else {
                        line
                    }
                }
                .joinToString(separator = "\n")
            metadataFile.writeText(corrupted)

            assertFalse(manager.restore(serializer = serializer, identity = identity))
            assertFalse(File(cacheDir, "${identity.cacheKey}.session").exists())
            assertFalse(File(cacheDir, "${identity.cacheKey}.session.meta").exists())
            assertEquals(0, serializer.loadCalls)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `restore rejects cache when identity metadata mismatches request`() {
        val cacheDir = createCacheDir()
        try {
            val manager = SessionCacheManager(cacheDir = cacheDir, maxTotalSizeBytes = 1_024L * 1_024L)
            val serializer = RecordingSessionCacheSerializer()
            val identity = sessionCacheIdentity()
            val mismatchedIdentity = identity.copy(modelVersion = "q4_1")

            assertTrue(manager.save(serializer = serializer, identity = identity))

            assertFalse(manager.hasCacheFor(mismatchedIdentity))
            assertFalse(manager.restore(serializer = serializer, identity = mismatchedIdentity))
            assertFalse(File(cacheDir, "${identity.cacheKey}.session").exists())
            assertFalse(File(cacheDir, "${identity.cacheKey}.session.meta").exists())
            assertEquals(0, serializer.loadCalls)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun sessionCacheIdentity(): SessionCacheIdentity {
        return SessionCacheIdentity(
            cacheKey = "cache-key",
            modelId = "model-a",
            modelVersion = "ud_iq2_xxs",
            modelPathHash = "path-hash",
            loadFingerprint = "load-fingerprint",
        )
    }

    private fun createCacheDir(): File = Files.createTempDirectory("session-cache-manager").toFile()
}

private fun parseMetadataEntries(serialized: String): Map<String, String> {
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

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private class RecordingSessionCacheSerializer : SessionCacheSerializer {
    var loadCalls: Int = 0

    override fun saveSessionCache(filePath: String): Boolean {
        File(filePath).writeText("cached-session")
        return true
    }

    override fun loadSessionCache(filePath: String): Boolean {
        loadCalls += 1
        return File(filePath).exists()
    }
}
