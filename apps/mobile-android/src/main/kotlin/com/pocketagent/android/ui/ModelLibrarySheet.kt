package com.pocketagent.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.runtime.ProvisioningReadiness
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.state.ModelLoadingState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelLibrarySheet(
    state: ModelLibraryUiState,
    modelLoadingState: ModelLoadingState,
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
    onRefreshAll: () -> Unit,
    onOpenRuntimeControls: () -> Unit,
    onClose: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterDownloaded by remember { mutableStateOf(false) }
    var filterInProgress by remember { mutableStateOf(false) }
    var filterAvailable by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("model_library_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = R.string.ui_model_library_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = onRefreshAll,
                    enabled = !state.isImporting,
                ) {
                    Text(stringResource(id = R.string.ui_model_refresh_all))
                }
            }
        }
        item {
            Text(
                text = stringResource(id = R.string.ui_model_library_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model_search_field"),
                placeholder = { Text(stringResource(id = R.string.ui_model_search_placeholder)) },
                singleLine = true,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filterDownloaded,
                    onClick = { filterDownloaded = !filterDownloaded },
                    label = { Text(stringResource(id = R.string.ui_model_filter_downloaded)) },
                )
                FilterChip(
                    selected = filterInProgress,
                    onClick = { filterInProgress = !filterInProgress },
                    label = { Text(stringResource(id = R.string.ui_model_filter_in_progress)) },
                )
                FilterChip(
                    selected = filterAvailable,
                    onClick = { filterAvailable = !filterAvailable },
                    label = { Text(stringResource(id = R.string.ui_model_filter_available)) },
                )
            }
        }
        if (state.snapshot.recoverableCorruptions.isNotEmpty()) {
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
                        state.snapshot.recoverableCorruptions.forEach { signal ->
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
        if (state.snapshot.readiness != ProvisioningReadiness.READY) {
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
                                id = if (state.snapshot.readiness == ProvisioningReadiness.BLOCKED) {
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
                                onClick = { state.defaultModelVersion?.let(onDownloadVersion) },
                                enabled = !state.isImporting && state.defaultModelVersion != null,
                            ) {
                                Text(stringResource(id = R.string.ui_model_get_ready_download_default))
                            }
                            OutlinedButton(
                                onClick = {
                                    state.defaultGetReadyModelId?.let(onImportModel)
                                },
                                enabled = !state.isImporting && !state.defaultGetReadyModelId.isNullOrBlank(),
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
        items(state.snapshot.models, key = { it.modelId }) { model ->
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
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_path_origin_label,
                                stringResource(id = model.pathOrigin.pathOriginLabelRes()),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        model.storageRootLabel?.takeIf { it.isNotBlank() }?.let { rootLabel ->
                            Text(
                                text = stringResource(
                                    id = R.string.ui_model_storage_root_label,
                                    rootLabel,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(id = R.string.ui_model_not_provisioned),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { onImportModel(model.modelId) },
                        enabled = !state.isImporting,
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
                    stringResource(id = state.manifest.source.readableNameRes()),
                    state.manifest.syncedAtEpochMs?.formatAsTimestamp()
                        ?: stringResource(id = R.string.ui_model_catalog_sync_unknown),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.manifest.lastError?.let { catalogError ->
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

        val allVersions = state.manifest.models
            .flatMap { it.versions }
            .distinctBy { version -> "${version.modelId}:${version.version}" }
        val versions = allVersions.filter { version ->
            val matchesSearch = searchQuery.isBlank() ||
                version.modelId.contains(searchQuery, ignoreCase = true) ||
                version.version.contains(searchQuery, ignoreCase = true)
            if (!matchesSearch) return@filter false
            if (!filterDownloaded && !filterInProgress && !filterAvailable) return@filter true
            val latestTask = state.downloads.firstOrNull {
                it.modelId == version.modelId && it.version == version.version
            }
            val isInstalled = latestTask?.terminal == true &&
                (latestTask.status == DownloadTaskStatus.COMPLETED || latestTask.status == DownloadTaskStatus.INSTALLED_INACTIVE)
            val isActive = latestTask != null && !latestTask.terminal
            val isAvail = latestTask == null || latestTask.terminal &&
                latestTask.status != DownloadTaskStatus.COMPLETED &&
                latestTask.status != DownloadTaskStatus.INSTALLED_INACTIVE
            (filterDownloaded && isInstalled) ||
                (filterInProgress && isActive) ||
                (filterAvailable && isAvail)
        }
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
            val latest = state.downloads.firstOrNull {
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
                        LinearProgressIndicator(
                            progress = { latest.progressPercent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        latest.transferSummary()?.let { transferSummary ->
                            Text(
                                text = transferSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        latest.stageWarningChips()
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
                            enabled = !state.isImporting && active == null,
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
                    if (latest != null && latest.status == DownloadTaskStatus.FAILED) {
                        Card {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(id = R.string.ui_model_download_failure_panel_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = latest.failureReasonMessage(version = version),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(onClick = { onRetryDownload(latest.taskId) }) {
                                        Text(stringResource(id = R.string.ui_model_download_retry))
                                    }
                                    OutlinedButton(onClick = { onCancelDownload(latest.taskId) }) {
                                        Text(stringResource(id = R.string.ui_cancel_button))
                                    }
                                    OutlinedButton(onClick = onRefreshManifest) {
                                        Text(stringResource(id = R.string.ui_model_refresh_manifest))
                                    }
                                }
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
        val filteredModels = state.snapshot.models.filter { model ->
            searchQuery.isBlank() ||
                model.modelId.contains(searchQuery, ignoreCase = true) ||
                model.displayName.contains(searchQuery, ignoreCase = true)
        }
        filteredModels.forEach { model ->
            items(
                model.installedVersions,
                key = { version ->
                    installedVersionItemKey(modelId = model.modelId, version = version.version)
                },
            ) { version ->
                val isLoadedVersion = modelLoadingState.loadedModel?.modelId == model.modelId &&
                    modelLoadingState.loadedModel?.modelVersion == version.version
                val isLoadingVersion = modelLoadingState is ModelLoadingState.Loading &&
                    (modelLoadingState as ModelLoadingState.Loading).requestedModel?.modelId == model.modelId &&
                    (modelLoadingState as ModelLoadingState.Loading).requestedModel?.modelVersion == version.version
                val dotColor = when {
                    isLoadedVersion -> MaterialTheme.colorScheme.primary
                    isLoadingVersion -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(dotColor),
                            )
                            Text(
                                text = stringResource(id = R.string.ui_model_installed_version_row, model.displayName, version.version),
                                modifier = Modifier.semantics {
                                    contentDescription = modelInstalledVersionLabel(model.modelId, version.version)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
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
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_path_origin_label,
                                stringResource(id = model.pathOriginForVersion(version.version).pathOriginLabelRes()),
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
                                    enabled = !state.isImporting,
                                ) {
                                    Text(stringResource(id = R.string.ui_model_activate_version))
                                }
                            }
                            OutlinedButton(
                                onClick = { onRemoveVersion(model.modelId, version.version) },
                                enabled = !version.isActive && !state.isImporting,
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
            val storage = state.snapshot.storageSummary
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(id = R.string.ui_model_storage_section_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (storage.totalBytes > 0L) {
                        val usedFraction = ((storage.usedByModelsBytes + storage.tempDownloadBytes).toFloat() / storage.totalBytes).coerceIn(0f, 1f)
                        val totalUsedFraction = (1f - (storage.freeBytes.toFloat() / storage.totalBytes)).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(MaterialTheme.shapes.small),
                        ) {
                            LinearProgressIndicator(
                                progress = { totalUsedFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { usedFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = if (usedFraction > 0.85f) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_storage_models_label,
                                storage.usedByModelsBytes.formatAsGiB(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_storage_free_label,
                                storage.freeBytes.formatAsGiB(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (storage.tempDownloadBytes > 0L) {
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_storage_temp_label,
                                storage.tempDownloadBytes.formatAsGiB(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(
                            id = R.string.ui_model_storage_total_label,
                            storage.totalBytes.formatAsGiB(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.snapshot.storageRootLabel?.takeIf { it.isNotBlank() }?.let { rootLabel ->
                        Text(
                            text = stringResource(id = R.string.ui_model_storage_root_label, rootLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.statusMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRefreshManifest,
                    enabled = !state.isImporting,
                ) {
                    Text(stringResource(id = R.string.ui_model_refresh_manifest))
                }
                OutlinedButton(
                    modifier = Modifier.testTag("open_runtime_controls_button"),
                    onClick = onOpenRuntimeControls,
                    enabled = !state.isImporting,
                ) {
                    Text(stringResource(id = R.string.ui_open_runtime_controls))
                }
                Button(
                    onClick = onRefreshRuntime,
                    enabled = !state.isImporting,
                ) {
                    Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                }
                Button(onClick = onClose, enabled = !state.isImporting) {
                    Text(stringResource(id = R.string.ui_close))
                }
            }
        }
    }
}
