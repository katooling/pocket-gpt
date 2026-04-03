package com.pocketagent.android.runtime.modelmanager

import com.pocketagent.core.model.ModelArtifactRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTaskStateTest {
    @Test
    fun `failed verification with unknown total still reports completed transfer percent`() {
        val state = fixture(
            status = DownloadTaskStatus.FAILED,
            processingStage = DownloadProcessingStage.VERIFYING,
            progressBytes = 1_024L,
            totalBytes = 0L,
        )

        assertEquals(100, state.progressPercent)
    }

    @Test
    fun `downloading with unknown total does not fake progress percent`() {
        val state = fixture(
            status = DownloadTaskStatus.DOWNLOADING,
            processingStage = DownloadProcessingStage.DOWNLOADING,
            progressBytes = 1_024L,
            totalBytes = 0L,
        )

        assertEquals(0, state.progressPercent)
    }

    @Test
    fun `strict verification policy enforces provenance checks`() {
        assertEquals(true, DownloadVerificationPolicy.PROVENANCE_STRICT.enforcesProvenance)
        assertEquals(false, DownloadVerificationPolicy.INTEGRITY_ONLY.enforcesProvenance)
    }

    @Test
    fun `throughput estimate is true only when speed and eta are present`() {
        val withMetrics = fixture(
            status = DownloadTaskStatus.DOWNLOADING,
            processingStage = DownloadProcessingStage.DOWNLOADING,
            progressBytes = 512L,
            totalBytes = 1024L,
        ).copy(
            downloadSpeedBps = 1024L,
            etaSeconds = 4L,
        )
        val withoutSpeed = withMetrics.copy(downloadSpeedBps = null)

        assertTrue(withMetrics.hasThroughputEstimate)
        assertFalse(withoutSpeed.hasThroughputEstimate)
    }

    @Test
    fun `legacy task synthesizes a primary artifact state`() {
        val state = fixture(
            status = DownloadTaskStatus.DOWNLOADING,
            processingStage = DownloadProcessingStage.DOWNLOADING,
            progressBytes = 512L,
            totalBytes = 1024L,
        )

        val artifacts = state.artifactStates

        assertEquals(1, artifacts.size)
        assertEquals(ModelArtifactRole.PRIMARY_GGUF, artifacts.single().role)
        assertEquals(512L, artifacts.single().progressBytes)
        assertEquals(1024L, artifacts.single().totalBytes)
    }

    @Test
    fun `explicit bundle artifacts are preserved`() {
        val bundleState = fixture(
            status = DownloadTaskStatus.DOWNLOADING,
            processingStage = DownloadProcessingStage.DOWNLOADING,
            progressBytes = 128L,
            totalBytes = 768L,
        ).copy(
            artifactStates = listOf(
                DownloadArtifactTaskState(
                    artifactId = "model::q4::primary",
                    role = ModelArtifactRole.PRIMARY_GGUF,
                    fileName = "model-q4.gguf",
                    downloadUrl = "https://example.test/model-q4.gguf",
                    expectedSha256 = "a".repeat(64),
                    provenanceIssuer = "",
                    provenanceSignature = "",
                    runtimeCompatibility = "android-arm64-v8a",
                    fileSizeBytes = 512L,
                    progressBytes = 128L,
                    totalBytes = 512L,
                    status = DownloadArtifactTaskStatus.DOWNLOADING,
                ),
                DownloadArtifactTaskState(
                    artifactId = "model::q4::mmproj",
                    role = ModelArtifactRole.MMPROJ,
                    fileName = "model-mmproj.gguf",
                    downloadUrl = "https://example.test/model-mmproj.gguf",
                    expectedSha256 = "b".repeat(64),
                    provenanceIssuer = "",
                    provenanceSignature = "",
                    runtimeCompatibility = "android-arm64-v8a",
                    fileSizeBytes = 256L,
                    totalBytes = 256L,
                    status = DownloadArtifactTaskStatus.PENDING,
                ),
            ),
        )

        val artifacts = bundleState.artifactStates

        assertEquals(2, artifacts.size)
        assertEquals(ModelArtifactRole.MMPROJ, artifacts.last().role)
        assertEquals(256L, artifacts.last().fileSizeBytes)
    }

    @Test
    fun `bundle total bytes sums all artifacts`() {
        val version = ModelDistributionVersion(
            modelId = "gemma-vision",
            version = "q4_k_m",
            downloadUrl = "https://example.test/gemma.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "",
            provenanceSignature = "",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 512L,
            artifacts = listOf(
                ModelDistributionArtifact(
                    artifactId = "gemma::primary",
                    role = ModelArtifactRole.PRIMARY_GGUF,
                    fileName = "gemma.gguf",
                    downloadUrl = "https://example.test/gemma.gguf",
                    expectedSha256 = "a".repeat(64),
                    provenanceIssuer = "",
                    provenanceSignature = "",
                    runtimeCompatibility = "android-arm64-v8a",
                    fileSizeBytes = 512L,
                ),
                ModelDistributionArtifact(
                    artifactId = "gemma::mmproj",
                    role = ModelArtifactRole.MMPROJ,
                    fileName = "gemma-mmproj.gguf",
                    downloadUrl = "https://example.test/gemma-mmproj.gguf",
                    expectedSha256 = "b".repeat(64),
                    provenanceIssuer = "",
                    provenanceSignature = "",
                    runtimeCompatibility = "android-arm64-v8a",
                    fileSizeBytes = 128L,
                ),
            ),
        )

        assertEquals(640L, version.bundleTotalBytes())
    }

    private fun fixture(
        status: DownloadTaskStatus,
        processingStage: DownloadProcessingStage,
        progressBytes: Long,
        totalBytes: Long,
    ): DownloadTaskState {
        return DownloadTaskState(
            taskId = "task-1",
            modelId = "qwen3.5-0.8b-q4",
            version = "q4_0",
            downloadUrl = "https://example.test/model.gguf",
            expectedSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            provenanceIssuer = "",
            provenanceSignature = "",
            verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
            runtimeCompatibility = "android-arm64-v8a",
            processingStage = processingStage,
            status = status,
            progressBytes = progressBytes,
            totalBytes = totalBytes,
            updatedAtEpochMs = 1L,
            failureReason = if (status == DownloadTaskStatus.FAILED) {
                DownloadFailureReason.CHECKSUM_MISMATCH
            } else {
                null
            },
            message = null,
        )
    }
}
