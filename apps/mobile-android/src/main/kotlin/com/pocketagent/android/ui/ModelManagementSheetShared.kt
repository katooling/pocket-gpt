package com.pocketagent.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pocketagent.android.R
import com.pocketagent.android.runtime.ModelPathOrigin
import com.pocketagent.android.runtime.modelmanager.DownloadFailureReason
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ManifestSource
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Legacy plain-text helper retained for JVM unit tests and non-Compose callers.
internal fun DownloadTaskState.readableStateName(): String {
    return when (status) {
        DownloadTaskStatus.QUEUED -> "Queued"
        DownloadTaskStatus.DOWNLOADING -> "Downloading"
        DownloadTaskStatus.PAUSED -> "Paused"
        DownloadTaskStatus.VERIFYING -> {
            if (processingStage == DownloadProcessingStage.INSTALLING) {
                "Installing"
            } else {
                "Verifying"
            }
        }
        DownloadTaskStatus.INSTALLED_INACTIVE -> "Verified (activation pending)"
        DownloadTaskStatus.FAILED -> {
            when (processingStage) {
                DownloadProcessingStage.DOWNLOADING -> "Failed during download"
                DownloadProcessingStage.VERIFYING -> "Failed during verification"
                DownloadProcessingStage.INSTALLING -> "Failed during install"
                DownloadProcessingStage.CORRUPT -> "Failed due to corrupt task metadata"
            }
        }
        DownloadTaskStatus.COMPLETED -> "Completed"
        DownloadTaskStatus.CANCELLED -> "Cancelled"
    }
}

@Composable
internal fun DownloadTaskState.readableStateNameLocalized(): String {
    return when (status) {
        DownloadTaskStatus.QUEUED -> stringResource(id = R.string.model_download_status_queued)
        DownloadTaskStatus.DOWNLOADING -> stringResource(id = R.string.model_download_status_downloading)
        DownloadTaskStatus.PAUSED -> stringResource(id = R.string.model_download_status_paused)
        DownloadTaskStatus.VERIFYING -> {
            if (processingStage == DownloadProcessingStage.INSTALLING) {
                stringResource(id = R.string.ui_model_stage_installing)
            } else {
                stringResource(id = R.string.model_download_status_verifying)
            }
        }
        DownloadTaskStatus.INSTALLED_INACTIVE -> stringResource(id = R.string.ui_model_installed_inactive)
        DownloadTaskStatus.FAILED -> {
            when (processingStage) {
                DownloadProcessingStage.DOWNLOADING -> stringResource(id = R.string.ui_model_stage_failure_download)
                DownloadProcessingStage.VERIFYING -> stringResource(id = R.string.ui_model_stage_failure_verification)
                DownloadProcessingStage.INSTALLING -> stringResource(id = R.string.ui_model_stage_failure_install)
                DownloadProcessingStage.CORRUPT -> stringResource(id = R.string.ui_model_stage_failure_corrupt)
            }
        }
        DownloadTaskStatus.COMPLETED -> stringResource(id = R.string.model_download_status_complete)
        DownloadTaskStatus.CANCELLED -> stringResource(id = R.string.model_download_status_cancelled)
    }
}

internal fun DownloadTaskState.transferSummary(): String? {
    val speed = downloadSpeedBps?.takeIf { it > 0L } ?: return null
    val eta = etaSeconds?.takeIf { it >= 0L }
    return buildString {
        append("${speed.formatAsPerSecond()}")
        eta?.let { seconds ->
            append(" • ETA ${seconds.formatAsEta()}")
        }
    }
}

@Composable
internal fun DownloadTaskState.stageWarningChips() {
    val chips = buildList {
        when (status) {
            DownloadTaskStatus.PAUSED -> add(stringResource(id = R.string.ui_model_stage_paused))
            DownloadTaskStatus.VERIFYING -> {
                add(
                    if (processingStage == DownloadProcessingStage.INSTALLING) {
                        stringResource(id = R.string.ui_model_stage_installing)
                    } else {
                        stringResource(id = R.string.ui_model_stage_verifying_integrity)
                    },
                )
            }
            DownloadTaskStatus.FAILED -> {
                add(
                    when (processingStage) {
                        DownloadProcessingStage.DOWNLOADING -> stringResource(id = R.string.ui_model_stage_failure_download)
                        DownloadProcessingStage.VERIFYING -> stringResource(id = R.string.ui_model_stage_failure_verification)
                        DownloadProcessingStage.INSTALLING -> stringResource(id = R.string.ui_model_stage_failure_install)
                        DownloadProcessingStage.CORRUPT -> stringResource(id = R.string.ui_model_stage_failure_corrupt)
                    },
                )
            }
            else -> Unit
        }
        if (failureReason == DownloadFailureReason.INSUFFICIENT_STORAGE) {
            add(stringResource(id = R.string.ui_model_stage_low_storage))
        }
        if (failureReason == DownloadFailureReason.TIMEOUT) {
            add(stringResource(id = R.string.ui_model_stage_timeout))
        }
    }
    if (chips.isEmpty()) {
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        chips.forEach { warning ->
            AssistChip(
                onClick = { },
                enabled = false,
                label = { Text(warning) },
            )
        }
    }
}

@Composable
internal fun DownloadTaskState.failureReasonMessage(version: ModelDistributionVersion): String {
    return when (failureReason) {
        DownloadFailureReason.CHECKSUM_MISMATCH -> stringResource(
            id = R.string.ui_model_download_failed_checksum,
            version.modelId,
            version.version,
        )
        DownloadFailureReason.PROVENANCE_MISMATCH -> stringResource(
            id = R.string.ui_model_download_failed_provenance,
            version.modelId,
            version.version,
        )
        DownloadFailureReason.RUNTIME_INCOMPATIBLE -> stringResource(
            id = R.string.ui_model_download_failed_runtime_compat,
            version.modelId,
            version.version,
        )
        DownloadFailureReason.INSUFFICIENT_STORAGE -> stringResource(
            id = R.string.ui_model_download_failed_storage,
            version.modelId,
            version.version,
        )
        DownloadFailureReason.NETWORK_UNAVAILABLE,
        DownloadFailureReason.NETWORK_ERROR,
        -> stringResource(
            id = R.string.ui_model_download_failed_network,
            version.modelId,
            version.version,
        )
        DownloadFailureReason.TIMEOUT -> stringResource(
            id = R.string.ui_model_download_failed_timeout,
            version.modelId,
            version.version,
        )
        DownloadFailureReason.CANCELLED -> stringResource(
            id = R.string.ui_model_download_failed_cancelled,
            version.modelId,
            version.version,
        )
        DownloadFailureReason.UNKNOWN,
        null,
        -> stringResource(
            id = R.string.ui_model_download_failed_unknown,
            version.modelId,
            version.version,
        )
    }
}

internal fun String.pathOriginLabelRes(): Int {
    return when (this) {
        ModelPathOrigin.MANAGED -> R.string.ui_model_path_origin_managed
        ModelPathOrigin.IMPORTED_EXTERNAL -> R.string.ui_model_path_origin_imported_external
        ModelPathOrigin.DISCOVERED_RECOVERED -> R.string.ui_model_path_origin_discovered_recovered
        else -> R.string.ui_model_path_origin_unknown
    }
}

internal fun ManifestSource.readableNameRes(): Int {
    return when (this) {
        ManifestSource.BUNDLED -> R.string.ui_model_catalog_source_bundled
        ManifestSource.REMOTE -> R.string.ui_model_catalog_source_remote
        ManifestSource.BUNDLED_AND_REMOTE -> R.string.ui_model_catalog_source_bundled_and_remote
    }
}

internal fun Long.formatAsTimestamp(): String {
    return runCatching {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
    }.getOrElse { toString() }
}

internal fun Long.formatAsGiB(): String {
    val gib = if (this <= 0L) {
        0.0
    } else {
        this.toDouble() / BYTES_PER_GIB
    }
    return String.format(Locale.US, "%.2f GB", gib)
}

internal fun Long.formatAsPerSecond(): String {
    val mib = this.toDouble() / (1024.0 * 1024.0)
    if (mib >= 1.0) {
        return String.format(Locale.US, "%.2f MB/s", mib)
    }
    val kib = this.toDouble() / 1024.0
    if (kib >= 1.0) {
        return String.format(Locale.US, "%.1f KB/s", kib)
    }
    return "$this B/s"
}

internal fun Long.formatAsEta(): String {
    val safe = coerceAtLeast(0L)
    val minutes = safe / 60L
    val seconds = safe % 60L
    return if (minutes > 0L) {
        "${minutes}m ${seconds}s"
    } else {
        "${seconds}s"
    }
}

internal fun downloadVersionItemKey(modelId: String, version: String): String {
    return "download:$modelId:$version"
}

internal fun installedVersionItemKey(modelId: String, version: String): String {
    return "installed:$modelId:$version"
}

private const val BYTES_PER_GIB: Double = 1024.0 * 1024.0 * 1024.0
