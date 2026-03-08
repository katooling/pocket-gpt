package com.pocketagent.android.runtime.modelmanager

data class ModelDistributionManifest(
    val models: List<ModelDistributionModel>,
    val source: ManifestSource = ManifestSource.BUNDLED,
    val syncedAtEpochMs: Long? = null,
    val lastError: String? = null,
)

enum class ManifestSource {
    BUNDLED,
    REMOTE,
    BUNDLED_AND_REMOTE,
}

data class ModelDistributionModel(
    val modelId: String,
    val displayName: String,
    val versions: List<ModelDistributionVersion>,
)

data class ModelDistributionVersion(
    val modelId: String,
    val version: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val runtimeCompatibility: String,
    val fileSizeBytes: Long,
)

data class ModelVersionDescriptor(
    val modelId: String,
    val version: String,
    val displayName: String,
    val absolutePath: String,
    val sha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val runtimeCompatibility: String,
    val fileSizeBytes: Long,
    val importedAtEpochMs: Long,
    val isActive: Boolean,
)

enum class DownloadTaskStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLED_INACTIVE,
    FAILED,
    COMPLETED,
    CANCELLED,
}

enum class DownloadVerificationPolicy {
    INTEGRITY_ONLY,
    UNKNOWN;

    val enforcesProvenance: Boolean
        get() = false
}

enum class DownloadProcessingStage {
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    CORRUPT,
}

enum class DownloadFailureReason {
    NETWORK_UNAVAILABLE,
    NETWORK_ERROR,
    TIMEOUT,
    INSUFFICIENT_STORAGE,
    CHECKSUM_MISMATCH,
    PROVENANCE_MISMATCH,
    RUNTIME_INCOMPATIBLE,
    CANCELLED,
    UNKNOWN,
}

data class DownloadTaskState(
    val taskId: String,
    val modelId: String,
    val version: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val provenanceIssuer: String,
    val provenanceSignature: String,
    val verificationPolicy: DownloadVerificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
    val runtimeCompatibility: String,
    val processingStage: DownloadProcessingStage = DownloadProcessingStage.DOWNLOADING,
    val status: DownloadTaskStatus,
    val progressBytes: Long,
    val totalBytes: Long,
    val updatedAtEpochMs: Long,
    val failureReason: DownloadFailureReason? = null,
    val message: String? = null,
) {
    val progressPercent: Int
        get() {
            if (totalBytes <= 0L) {
                if (progressBytes <= 0L) {
                    return 0
                }
                if (processingStage != DownloadProcessingStage.DOWNLOADING || status == DownloadTaskStatus.FAILED) {
                    return 100
                }
                return 0
            }
            return ((progressBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
        }

    val terminal: Boolean
        get() = status == DownloadTaskStatus.FAILED ||
            status == DownloadTaskStatus.CANCELLED ||
            status == DownloadTaskStatus.COMPLETED ||
            status == DownloadTaskStatus.INSTALLED_INACTIVE
}

data class StorageSummary(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedByModelsBytes: Long,
    val tempDownloadBytes: Long,
)
