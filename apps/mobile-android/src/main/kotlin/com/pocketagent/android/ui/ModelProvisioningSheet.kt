package com.pocketagent.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.runtime.ProvisioningReadiness
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ManifestSource
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ModelProvisioningSheet(
    snapshot: RuntimeProvisioningSnapshot,
    manifest: ModelDistributionManifest,
    downloads: List<DownloadTaskState>,
    isImporting: Boolean,
    statusMessage: String?,
    defaultGetReadyModelId: String?,
    onImportModel: (String) -> Unit,
    onDownloadVersion: (ModelDistributionVersion) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onActivateVersion: (String, String) -> Unit,
    onRemoveVersion: (String, String) -> Unit,
    onRefreshManifest: () -> Unit,
    onRefreshRuntime: () -> Unit,
    onClose: () -> Unit,
) {
    val defaultModelVersion = resolveDefaultGetReadyVersion(
        manifest = manifest,
        defaultModelId = defaultGetReadyModelId,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("model_provisioning_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(id = R.string.ui_model_provisioning_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Text(
                text = stringResource(id = R.string.ui_model_provisioning_subtitle_v2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.recoverableCorruptions.isNotEmpty()) {
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(id = R.string.ui_model_recovery_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        snapshot.recoverableCorruptions.forEach { signal ->
                            Text(
                                text = "${signal.message} (${signal.code})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onRefreshRuntime) {
                            Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                        }
                    }
                }
            }
        }
        if (snapshot.readiness != ProvisioningReadiness.READY) {
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(id = R.string.ui_model_get_ready_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                id = if (snapshot.readiness == ProvisioningReadiness.BLOCKED) {
                                    R.string.ui_model_get_ready_blocked_body
                                } else {
                                    R.string.ui_model_get_ready_degraded_body
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { defaultModelVersion?.let(onDownloadVersion) },
                                enabled = !isImporting && defaultModelVersion != null,
                            ) {
                                Text(stringResource(id = R.string.ui_model_get_ready_download_default))
                            }
                            OutlinedButton(
                                onClick = {
                                    defaultGetReadyModelId?.let { modelId -> onImportModel(modelId) }
                                },
                                enabled = !isImporting && !defaultGetReadyModelId.isNullOrBlank(),
                            ) {
                                Text(stringResource(id = R.string.ui_model_get_ready_import_default))
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(id = R.string.ui_model_required_models_section),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(snapshot.models, key = { it.modelId }) { model ->
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(model.displayName, style = MaterialTheme.typography.labelLarge)
                    if (model.isProvisioned) {
                        Text(
                            text = stringResource(id = R.string.ui_model_active_version_label, model.activeVersion.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(id = R.string.ui_model_provisioned_path, model.absolutePath.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_provisioned_sha,
                                model.sha256?.take(12).orEmpty(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(id = R.string.ui_model_not_provisioned),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { onImportModel(model.modelId) },
                        enabled = !isImporting,
                    ) {
                        Text(
                            text = stringResource(
                                id = if (model.isProvisioned) {
                                    R.string.ui_model_replace
                                } else {
                                    R.string.ui_model_import
                                },
                            ),
                        )
                    }
                }
            }
        }

        item { HorizontalDivider() }

        item {
            Text(
                text = stringResource(id = R.string.ui_model_downloads_section),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Text(
                text = stringResource(
                    id = R.string.ui_model_catalog_sync_summary,
                    stringResource(id = manifest.source.readableNameRes()),
                    manifest.syncedAtEpochMs?.formatAsTimestamp()
                        ?: stringResource(id = R.string.ui_model_catalog_sync_unknown),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        manifest.lastError?.let { catalogError ->
            item {
                Text(
                    text = stringResource(
                        id = R.string.ui_model_catalog_sync_warning,
                        catalogError,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        val versions = manifest.models
            .flatMap { it.versions }
            .distinctBy { version -> "${version.modelId}:${version.version}" }
        if (versions.isEmpty()) {
            item {
                Text(
                    text = stringResource(id = R.string.ui_model_downloads_manifest_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(
            versions,
            key = { version -> downloadVersionItemKey(modelId = version.modelId, version = version.version) },
        ) { version ->
            val latest = downloads.firstOrNull {
                it.modelId == version.modelId && it.version == version.version
            }
            val active = latest?.takeIf {
                it.status == DownloadTaskStatus.QUEUED ||
                    it.status == DownloadTaskStatus.DOWNLOADING ||
                    it.status == DownloadTaskStatus.PAUSED ||
                    it.status == DownloadTaskStatus.VERIFYING
            }
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(id = R.string.ui_model_download_version_label, version.modelId, version.version),
                        modifier = Modifier.semantics {
                            contentDescription = modelDownloadVersionLabel(version.modelId, version.version)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            id = R.string.ui_model_download_expected_size,
                            version.fileSizeBytes.formatAsGiB(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (latest != null) {
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_download_state,
                                latest.readableStateName(),
                                latest.progressPercent,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        latest.message?.takeIf { it.isNotBlank() }?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            modifier = Modifier.semantics {
                                contentDescription = modelDownloadStartLabel(version.modelId, version.version)
                            },
                            onClick = { onDownloadVersion(version) },
                            enabled = !isImporting && active == null,
                        ) {
                            Text(stringResource(id = R.string.ui_model_download_start))
                        }
                        if (active != null && (
                                active.status == DownloadTaskStatus.DOWNLOADING ||
                                    active.status == DownloadTaskStatus.QUEUED
                                )
                        ) {
                            OutlinedButton(onClick = { onPauseDownload(active.taskId) }) {
                                Text(stringResource(id = R.string.ui_model_download_pause))
                            }
                        }
                        if (active != null && active.status == DownloadTaskStatus.PAUSED) {
                            OutlinedButton(onClick = { onResumeDownload(active.taskId) }) {
                                Text(stringResource(id = R.string.ui_model_download_resume))
                            }
                        }
                        if (active != null) {
                            OutlinedButton(onClick = { onCancelDownload(active.taskId) }) {
                                Text(stringResource(id = R.string.ui_cancel_button))
                            }
                        }
                        if (latest != null && latest.status == DownloadTaskStatus.FAILED) {
                            OutlinedButton(onClick = { onRetryDownload(latest.taskId) }) {
                                Text(stringResource(id = R.string.ui_model_download_retry))
                            }
                        }
                    }
                }
            }
        }

        item { HorizontalDivider() }

        item {
            Text(
                text = stringResource(id = R.string.ui_model_installed_versions_section),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Text(
                text = stringResource(id = R.string.ui_model_installed_versions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        snapshot.models.forEach { model ->
            items(
                model.installedVersions,
                key = { version ->
                    installedVersionItemKey(modelId = model.modelId, version = version.version)
                },
            ) { version ->
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(id = R.string.ui_model_installed_version_row, model.displayName, version.version),
                            modifier = Modifier.semantics {
                                contentDescription = modelInstalledVersionLabel(model.modelId, version.version)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                id = if (version.isActive) {
                                    R.string.ui_model_installed_active
                                } else {
                                    R.string.ui_model_installed_inactive
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (version.isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_installed_added_at,
                                version.importedAtEpochMs.formatAsTimestamp(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_provisioned_sha,
                                version.sha256.take(12),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_provisioned_path,
                                version.absolutePath,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (version.isActive) {
                                Text(
                                    text = stringResource(id = R.string.ui_model_current_active_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                OutlinedButton(
                                    modifier = Modifier.semantics {
                                        contentDescription = modelActivateVersionLabel(model.modelId, version.version)
                                    },
                                    onClick = { onActivateVersion(model.modelId, version.version) },
                                    enabled = !isImporting,
                                ) {
                                    Text(stringResource(id = R.string.ui_model_activate_version))
                                }
                            }
                            OutlinedButton(
                                onClick = { onRemoveVersion(model.modelId, version.version) },
                                enabled = !version.isActive && !isImporting,
                            ) {
                                Text(stringResource(id = R.string.ui_model_remove_version))
                            }
                        }
                    }
                }
            }
        }

        item { HorizontalDivider() }

        item {
            val storage = snapshot.storageSummary
            Text(
                text = stringResource(
                    id = R.string.ui_model_storage_summary,
                    storage.totalBytes,
                    storage.freeBytes,
                    storage.usedByModelsBytes,
                    storage.tempDownloadBytes,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        statusMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = onRefreshManifest,
                    enabled = !isImporting,
                ) {
                    Text(stringResource(id = R.string.ui_model_refresh_manifest))
                }
                Button(
                    onClick = onRefreshRuntime,
                    enabled = !isImporting,
                ) {
                    Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                }
                Button(onClick = onClose, enabled = !isImporting) {
                    Text(stringResource(id = R.string.ui_close))
                }
            }
        }
    }
}

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

private fun ManifestSource.readableNameRes(): Int {
    return when (this) {
        ManifestSource.BUNDLED -> R.string.ui_model_catalog_source_bundled
        ManifestSource.REMOTE -> R.string.ui_model_catalog_source_remote
        ManifestSource.BUNDLED_AND_REMOTE -> R.string.ui_model_catalog_source_bundled_and_remote
    }
}

private fun modelDownloadVersionLabel(modelId: String, version: String): String =
    "Download version ${modelId} ${version}"

private fun modelDownloadStartLabel(modelId: String, version: String): String =
    "Start download ${modelId} ${version}"

private fun modelInstalledVersionLabel(modelId: String, version: String): String =
    "Installed version ${modelId} ${version}"

private fun modelActivateVersionLabel(modelId: String, version: String): String =
    "Activate version ${modelId} ${version}"

private fun Long.formatAsTimestamp(): String {
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

private const val BYTES_PER_GIB: Double = 1024.0 * 1024.0 * 1024.0

internal fun downloadVersionItemKey(modelId: String, version: String): String {
    return "download:$modelId:$version"
}

internal fun installedVersionItemKey(modelId: String, version: String): String {
    return "installed:$modelId:$version"
}
