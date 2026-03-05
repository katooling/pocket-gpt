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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ModelProvisioningSheet(
    snapshot: RuntimeProvisioningSnapshot,
    manifest: ModelDistributionManifest,
    downloads: List<DownloadTaskState>,
    isImporting: Boolean,
    statusMessage: String?,
    onImportModel: (String) -> Unit,
    onDownloadVersion: (ModelDistributionVersion) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onActivateVersion: (String, String) -> Unit,
    onRemoveVersion: (String, String) -> Unit,
    onRefreshManifest: () -> Unit,
    onRefreshRuntime: () -> Unit,
    onClose: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
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

        val versions = manifest.models.flatMap { it.versions }
        if (versions.isEmpty()) {
            item {
                Text(
                    text = stringResource(id = R.string.ui_model_downloads_manifest_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(versions, key = { "${it.modelId}:${it.version}" }) { version ->
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
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(id = R.string.ui_model_download_expected_size, version.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (latest != null) {
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_download_state,
                                latest.status.readableName(),
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
            items(model.installedVersions, key = { "${model.modelId}:${it.version}" }) { version ->
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(id = R.string.ui_model_installed_version_row, model.displayName, version.version),
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

private fun DownloadTaskStatus.readableName(): String {
    return when (this) {
        DownloadTaskStatus.QUEUED -> "Queued"
        DownloadTaskStatus.DOWNLOADING -> "Downloading"
        DownloadTaskStatus.PAUSED -> "Paused"
        DownloadTaskStatus.VERIFYING -> "Verifying"
        DownloadTaskStatus.INSTALLED_INACTIVE -> "Verified (activation pending)"
        DownloadTaskStatus.FAILED -> "Failed"
        DownloadTaskStatus.COMPLETED -> "Completed"
        DownloadTaskStatus.CANCELLED -> "Cancelled"
    }
}

private fun Long.formatAsTimestamp(): String {
    return runCatching {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
    }.getOrElse { toString() }
}
