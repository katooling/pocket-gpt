package com.pocketagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelArtifactManagerTest {
    @Test
    fun `checksum verification returns pass and mismatch statuses`() {
        val manager = ModelArtifactManager()
        val bytes = "artifact-v1".encodeToByteArray()
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-a",
                version = "1.0.0",
                fileName = "model-a-v1.gguf",
                expectedSha256 = manager.sha256Hex(bytes),
            ),
        )

        val pass = manager.verifyChecksumResult("model-a", bytes)
        val fail = manager.verifyChecksumResult("model-a", "1.0.0", "artifact-corrupt".encodeToByteArray())

        assertEquals(ChecksumVerificationStatus.PASS, pass.status)
        assertEquals("1.0.0", pass.version)
        assertTrue(pass.passed)
        assertEquals(ChecksumVerificationStatus.CHECKSUM_MISMATCH, fail.status)
        assertFalse(fail.passed)
        assertFalse(manager.verifyChecksum("model-a", "artifact-corrupt".encodeToByteArray()))
    }

    @Test
    fun `unknown model and unknown version are handled explicitly`() {
        val manager = ModelArtifactManager()
        val baselineBytes = "qwen-baseline".encodeToByteArray()
        manager.registerArtifact(
            ModelArtifact(
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                version = "1.0.0",
                fileName = "qwen-0dot8b-v1.gguf",
                expectedSha256 = manager.sha256Hex(baselineBytes),
            ),
        )
        assertTrue(manager.setActiveModel(ModelCatalog.QWEN_3_5_0_8B_Q4))

        val unknownModel = manager.verifyChecksumResult("missing-model", "bytes".encodeToByteArray())
        val unknownVersion = manager.verifyChecksumResult(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "9.9.9",
            bytes = baselineBytes,
        )

        assertEquals(ChecksumVerificationStatus.UNKNOWN_MODEL, unknownModel.status)
        assertEquals(ChecksumVerificationStatus.UNKNOWN_VERSION, unknownVersion.status)
        assertFalse(manager.setActiveModel("missing-model"))
        assertFalse(manager.setActiveModelVersion(ModelCatalog.QWEN_3_5_0_8B_Q4, "9.9.9"))
        assertEquals(ModelCatalog.QWEN_3_5_0_8B_Q4, manager.getActiveModel())
    }

    @Test
    fun `active version selection is deterministic for edge versions`() {
        val manager = ModelArtifactManager()
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-b",
                version = "1.2.0-rc.1",
                fileName = "model-b-1.2.0-rc1.gguf",
                expectedSha256 = manager.sha256Hex("model-b-rc".encodeToByteArray()),
            ),
        )
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-b",
                version = "1.2",
                fileName = "model-b-1.2.gguf",
                expectedSha256 = manager.sha256Hex("model-b-1.2".encodeToByteArray()),
            ),
        )
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-b",
                version = "1.2.0",
                fileName = "model-b-1.2.0.gguf",
                expectedSha256 = manager.sha256Hex("model-b-1.2.0".encodeToByteArray()),
            ),
        )
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-b",
                version = "1.10.0",
                fileName = "model-b-1.10.0.gguf",
                expectedSha256 = manager.sha256Hex("model-b-1.10.0".encodeToByteArray()),
            ),
        )

        assertTrue(manager.setActiveModel("model-b"))
        assertEquals("1.10.0", manager.getActiveModelVersion())

        assertTrue(manager.setActiveModelVersion("model-b", "1.2.0-rc.1"))
        assertEquals("1.2.0-rc.1", manager.getActiveModelVersion())

        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-b",
                version = "2.0.0",
                fileName = "model-b-2.0.0.gguf",
                expectedSha256 = manager.sha256Hex("model-b-2.0.0".encodeToByteArray()),
            ),
        )
        assertEquals("1.2.0-rc.1", manager.getActiveModelVersion())
        assertTrue(manager.setActiveModel("model-b"))
        assertEquals("2.0.0", manager.getActiveModelVersion())
    }

    @Test
    fun `manifest output is structured and sorted deterministically`() {
        val manager = ModelArtifactManager()
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-z",
                version = "1.0.0",
                fileName = "model-z-v1.gguf",
                expectedSha256 = manager.sha256Hex("model-z-v1".encodeToByteArray()),
            ),
        )
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-a",
                version = "2.0.0",
                fileName = "model-a-v2.gguf",
                expectedSha256 = manager.sha256Hex("model-a-v2".encodeToByteArray()),
            ),
        )
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-a",
                version = "1.0.0",
                fileName = "model-a-v1.gguf",
                expectedSha256 = manager.sha256Hex("model-a-v1".encodeToByteArray()),
            ),
        )
        assertTrue(manager.setActiveModel("model-a"))

        val manifest = manager.buildManifest()

        assertEquals(listOf("model-a", "model-z"), manifest.models.map { it.modelId })
        assertEquals("2.0.0", manifest.models.first().latestVersion)
        assertEquals(listOf("1.0.0", "2.0.0"), manifest.models.first().artifacts.map { it.version })
        assertEquals("model-a", manifest.activeModelId)
        assertEquals("2.0.0", manifest.activeVersion)
        assertEquals(
            listOf("model-a", "model-a", "model-z"),
            manager.listArtifacts().map { it.modelId },
        )
    }

    @Test
    fun `manifest validation flags invalid checksum format`() {
        val manager = ModelArtifactManager()
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-invalid",
                version = "1.0.0",
                fileName = "model-invalid.gguf",
                expectedSha256 = "not-a-valid-sha",
            ),
        )

        val issues = manager.validateManifest()
        val checksumIssue = issues.firstOrNull { it.code == "INVALID_SHA256" }

        assertNotNull(checksumIssue)
        assertEquals("model-invalid", checksumIssue.modelId)
        assertEquals("1.0.0", checksumIssue.version)
    }

    @Test
    fun `manifest validation passes for real-format checksum metadata`() {
        val manager = ModelArtifactManager()
        manager.registerArtifact(
            ModelArtifact(
                modelId = "model-valid",
                version = "1.0.0",
                fileName = "model-valid.gguf",
                expectedSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )

        assertTrue(manager.validateManifest().isEmpty())
    }
}
