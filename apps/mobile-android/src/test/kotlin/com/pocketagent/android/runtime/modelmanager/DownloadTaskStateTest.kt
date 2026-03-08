package com.pocketagent.android.runtime.modelmanager

import kotlin.test.Test
import kotlin.test.assertEquals

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
