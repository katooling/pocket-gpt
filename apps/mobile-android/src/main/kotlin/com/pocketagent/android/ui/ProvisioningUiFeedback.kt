package com.pocketagent.android.ui

import android.content.Context
import android.util.Log
import com.pocketagent.android.R
import com.pocketagent.android.runtime.errorCodeName
import com.pocketagent.android.runtime.modelmanager.DownloadArtifactTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadFailureReason
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult

internal suspend fun startModelDownload(
    context: Context,
    version: ModelDistributionVersion,
    enqueueDownload: suspend (ModelDistributionVersion) -> String,
    onStatus: (String) -> Unit,
) {
    runCatching { enqueueDownload(version) }
        .onSuccess { taskId ->
            onStatus(
                context.getString(
                    R.string.ui_model_download_enqueued,
                    version.modelId,
                    version.version,
                    taskId,
                ),
            )
        }
        .onFailure { error ->
            onStatus(
                context.getString(
                    R.string.ui_model_download_start_failed,
                    version.modelId,
                    version.version,
                    error.message ?: "unknown error",
                ),
            )
        }
}

internal fun lifecycleStatusMessage(
    context: Context,
    result: RuntimeModelLifecycleCommandResult,
    fallbackModelId: String?,
    fallbackVersion: String?,
): String {
    if (result.success) {
        if (result.queued) {
            return context.getString(R.string.ui_model_runtime_offload_queued)
        }
        val loaded = result.loadedModel
        if (loaded != null) {
            return context.getString(
                R.string.ui_model_runtime_loaded_message,
                loaded.modelId,
                loaded.modelVersion.orEmpty(),
            )
        }
        return context.getString(R.string.ui_model_runtime_offloaded_message)
    }
    val modelId = fallbackModelId.orEmpty()
    val version = fallbackVersion.orEmpty()
    return when (result.errorCodeName()) {
        "MODEL_FILE_UNAVAILABLE" -> context.getString(
            R.string.ui_model_runtime_error_model_file_unavailable,
            modelId,
            version,
        )
        "RUNTIME_INCOMPATIBLE" -> context.getString(
            R.string.ui_model_runtime_error_incompatible,
            modelId,
            version,
        )
        "BACKEND_INIT_FAILED" -> context.getString(
            R.string.ui_model_runtime_error_backend_init,
            result.detail ?: "backend_init_failed",
        )
        "OUT_OF_MEMORY" -> context.getString(
            R.string.ui_model_runtime_error_out_of_memory,
            result.detail ?: "out_of_memory",
        )
        "BUSY_GENERATION" -> context.getString(R.string.ui_model_runtime_error_busy_generation)
        "CANCELLED_BY_NEWER_REQUEST" -> context.getString(
            R.string.ui_model_runtime_error_cancelled_newer_request,
        )
        null,
        "UNKNOWN",
        -> context.getString(
            R.string.ui_model_runtime_error_unknown,
            result.detail ?: "unknown",
        )
        else -> context.getString(
            R.string.ui_model_runtime_error_unknown,
            result.detail ?: result.errorCodeName()?.lowercase() ?: "unknown",
        )
    }
}

internal fun DownloadTaskState.provisioningFeedback(context: Context): String? {
    return when (status) {
        DownloadTaskStatus.COMPLETED -> {
            val hasSkippedOptional = artifactStates.any { artifact ->
                !artifact.required && artifact.status == DownloadArtifactTaskStatus.FAILED
            }
            if (hasSkippedOptional) {
                context.getString(
                    R.string.ui_model_download_completed_vision_degraded,
                    modelId,
                    version,
                )
            } else {
                context.getString(
                    R.string.ui_model_download_verified_active,
                    modelId,
                    version,
                )
            }
        }
        DownloadTaskStatus.INSTALLED_INACTIVE -> context.getString(
            R.string.ui_model_download_verified_inactive,
            modelId,
            version,
        )
        DownloadTaskStatus.FAILED -> {
            when (failureReason) {
                DownloadFailureReason.CHECKSUM_MISMATCH -> context.getString(
                    R.string.ui_model_download_failed_checksum,
                    modelId,
                    version,
                )
                DownloadFailureReason.PROVENANCE_MISMATCH -> context.getString(
                    R.string.ui_model_download_failed_provenance,
                    modelId,
                    version,
                )
                DownloadFailureReason.RUNTIME_INCOMPATIBLE -> context.getString(
                    R.string.ui_model_download_failed_runtime_compat,
                    modelId,
                    version,
                )
                DownloadFailureReason.INSUFFICIENT_STORAGE -> context.getString(
                    R.string.ui_model_download_failed_storage,
                    modelId,
                    version,
                )
                DownloadFailureReason.NETWORK_UNAVAILABLE,
                DownloadFailureReason.NETWORK_ERROR,
                -> context.getString(
                    R.string.ui_model_download_failed_network,
                    modelId,
                    version,
                )
                DownloadFailureReason.TIMEOUT -> context.getString(
                    R.string.ui_model_download_failed_timeout,
                    modelId,
                    version,
                )
                DownloadFailureReason.CANCELLED -> context.getString(
                    R.string.ui_model_download_failed_cancelled,
                    modelId,
                    version,
                )
                DownloadFailureReason.UNKNOWN,
                null,
                -> context.getString(
                    R.string.ui_model_download_failed_unknown,
                    modelId,
                    version,
                )
            }
        }
        else -> null
    }
}

internal fun logProvisioningTransition(
    phase: String,
    eventId: String,
    detail: String,
) {
    runCatching {
        Log.i("PocketAgentApp", "MODEL_TRANSITION|phase=$phase|event_id=$eventId|detail=$detail")
    }
}
