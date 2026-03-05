package com.pocketagent.android.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.inference.ModelCatalog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRuntimeProvisioningStoreInstrumentationTest {
    private lateinit var appContext: android.content.Context
    private lateinit var store: AndroidRuntimeProvisioningStore

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        resetProvisioningStorage()
        store = AndroidRuntimeProvisioningStore(appContext)
    }

    @After
    fun tearDown() {
        resetProvisioningStorage()
    }

    @Test
    fun seedingSamePathReplacesPreviousVersionEntry() = runBlocking {
        val source = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "seed-same-path.gguf",
            content = "same-path-seed-content",
        )
        store.seedModelFromAbsolutePath(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            absolutePath = source.absolutePath,
            version = "seed-v1",
        )
        val latest = store.seedModelFromAbsolutePath(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            absolutePath = source.absolutePath,
            version = "seed-v2",
        )

        val versions = store.listInstalledVersions(ModelCatalog.QWEN_3_5_0_8B_Q4)
        val normalizedSourcePath = normalizePath(source.absolutePath)
        assertEquals(
            "Expected one canonical entry for a repeatedly seeded source path.",
            1,
            versions.count { normalizePath(it.absolutePath) == normalizedSourcePath },
        )
        assertEquals("seed-v2", latest.version)
        assertEquals("seed-v2", versions.single().version)
    }

    @Test
    fun removeInactiveSeededVersionDoesNotDeleteExternalSourcePath() = runBlocking {
        val oldSource = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "seed-old.gguf",
            content = "old-seed",
        )
        val newSource = writeTempFile(
            dir = appContext.cacheDir,
            fileName = "seed-new.gguf",
            content = "new-seed",
        )

        store.seedModelFromAbsolutePath(
            modelId = ModelCatalog.QWEN_3_5_2B_Q4,
            absolutePath = oldSource.absolutePath,
            version = "seed-old",
        )
        store.seedModelFromAbsolutePath(
            modelId = ModelCatalog.QWEN_3_5_2B_Q4,
            absolutePath = newSource.absolutePath,
            version = "seed-new",
        )

        val versionsBefore = store.listInstalledVersions(ModelCatalog.QWEN_3_5_2B_Q4)
        val inactive = versionsBefore.first { !it.isActive }
        assertTrue(
            "Expected inactive seeded source path to be removed from metadata.",
            store.removeVersion(ModelCatalog.QWEN_3_5_2B_Q4, inactive.version),
        )

        val versionsAfter = store.listInstalledVersions(ModelCatalog.QWEN_3_5_2B_Q4)
        assertEquals(1, versionsAfter.size)
        assertTrue(
            "Seeded source paths should not be deleted by removeVersion.",
            oldSource.exists(),
        )
        assertTrue(newSource.exists())
    }

    @Test
    fun removeInactiveManagedVersionDeletesOrphanedManagedFile() {
        val managedDir = File(appContext.filesDir, "runtime-models").apply { mkdirs() }
        val oldManaged = writeTempFile(
            dir = managedDir,
            fileName = "managed-old.gguf",
            content = "managed-old",
        )
        val newManaged = writeTempFile(
            dir = managedDir,
            fileName = "managed-new.gguf",
            content = "managed-new",
        )

        store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "managed-old",
            absolutePath = oldManaged.absolutePath,
            sha256 = sha256Hex(oldManaged),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = oldManaged.length(),
            makeActive = true,
        )
        store.installDownloadedModel(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "managed-new",
            absolutePath = newManaged.absolutePath,
            sha256 = sha256Hex(newManaged),
            provenanceIssuer = "internal-release",
            provenanceSignature = "",
            runtimeCompatibility = store.expectedRuntimeCompatibilityTag(),
            fileSizeBytes = newManaged.length(),
            makeActive = true,
        )

        assertTrue(store.removeVersion(ModelCatalog.QWEN_3_5_0_8B_Q4, "managed-old"))
        assertFalse(
            "Orphaned managed model files should be deleted when removed from installed versions.",
            oldManaged.exists(),
        )
        assertTrue(newManaged.exists())
    }

    private fun writeTempFile(dir: File, fileName: String, content: String): File {
        dir.mkdirs()
        return File(dir, fileName).apply { writeText(content) }
    }

    private fun resetProvisioningStorage() {
        appContext.getSharedPreferences("pocketagent_runtime_models", 0).edit().clear().commit()
        File(appContext.filesDir, "runtime-models").deleteRecursively()
        File(appContext.filesDir, "model-downloads").deleteRecursively()
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun normalizePath(value: String): String {
        return runCatching { File(value).canonicalPath }.getOrElse { File(value).absolutePath }
    }
}
