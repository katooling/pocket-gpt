package com.pocketagent.android.runtime.modelmanager

data class ModelDistributionManifest(
    val models: List<ModelDistributionModel>,
)

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
    val runtimeCompatibility: String,
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
