package com.pocketagent.android.ui

import com.pocketagent.android.runtime.modelmanager.DownloadFailureReason
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.ModelPathOrigin
import com.pocketagent.android.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelProvisioningSheetStateLabelTest {
    @Test
    fun `failed verification shows stage specific label`() {
        val state = fixture(
            status = DownloadTaskStatus.FAILED,
            processingStage = DownloadProcessingStage.VERIFYING,
        )

        assertEquals("Failed during verification", state.readableStateName())
    }

    @Test
    fun `verifying with install stage shows installing label`() {
        val state = fixture(
            status = DownloadTaskStatus.VERIFYING,
            processingStage = DownloadProcessingStage.INSTALLING,
        )

        assertEquals("Installing", state.readableStateName())
    }

    @Test
    fun `transfer summary renders speed and eta`() {
        val state = fixture(
            status = DownloadTaskStatus.DOWNLOADING,
            processingStage = DownloadProcessingStage.DOWNLOADING,
        ).copy(
            downloadSpeedBps = 2L * 1024L * 1024L,
            etaSeconds = 125L,
        )

        val summary = state.transferSummary()

        assertTrue(summary?.contains("MB/s") == true)
        assertTrue(summary?.contains("ETA") == true)
    }

    @Test
    fun `pathOriginLabelRes maps path origins`() {
        assertEquals(R.string.ui_model_path_origin_managed, ModelPathOrigin.MANAGED.pathOriginLabelRes())
        assertEquals(R.string.ui_model_path_origin_imported_external, ModelPathOrigin.IMPORTED_EXTERNAL.pathOriginLabelRes())
        assertEquals(R.string.ui_model_path_origin_discovered_recovered, ModelPathOrigin.DISCOVERED_RECOVERED.pathOriginLabelRes())
    }

    private fun fixture(
        status: DownloadTaskStatus,
        processingStage: DownloadProcessingStage,
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
            progressBytes = 100L,
            totalBytes = 100L,
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
