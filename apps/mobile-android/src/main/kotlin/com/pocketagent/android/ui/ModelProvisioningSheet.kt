package com.pocketagent.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.runtime.ModelPathOrigin
import com.pocketagent.android.runtime.ProvisioningReadiness
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadFailureReason
import com.pocketagent.android.runtime.modelmanager.DownloadProcessingStage
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ManifestSource
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.nativebridge.ModelLifecycleState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ModelProvisioningSheet(
    snapshot: RuntimeProvisioningSnapshot,
    lifecycle: RuntimeModelLifecycleSnapshot,
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
    onLoadVersion: (String, String) -> Unit,
    onLoadLastUsedModel: () -> Unit,
    onOffloadModel: () -> Unit,
    onRemoveVersion: (String, String) -> Unit,
    onRefreshManifest: () -> Unit,
    onRefreshRuntime: () -> Unit,
    onRefreshAll: () -> Unit,
    onClose: () -> Unit,
) {
    val defaultModelVersion = resolveDefaultGetReadyVersion(
        manifest = manifest,
        defaultModelId = defaultGetReadyModelId,
    )

    var searchQuery by remember { mutableStateOf("") }
    var filterDownloaded by remember { mutableStateOf(false) }
    var filterInProgress by remember { mutableStateOf(false) }
    var filterAvailable by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("model_provisioning_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = R.string.ui_model_provisioning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = onRefreshAll,
                    enabled = !isImporting,
                ) {
                    Text(stringResource(id = R.string.ui_model_refresh_all))
                }
            }
        }
        item {
            Text(
                text = stringResource(id = R.string.ui_model_provisioning_subtitle_v2),
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
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(id = R.string.ui_model_runtime_lifecycle_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            id = R.string.ui_model_runtime_lifecycle_status,
                            lifecycle.readableRuntimeStateLabel(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    lifecycle.loadedModel?.let { loaded ->
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_runtime_loaded_version_label,
                                loaded.modelId,
                                loaded.modelVersion.orEmpty(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (lifecycle.loadedModel == null && lifecycle.lastUsedModel != null) {
                        OutlinedButton(
                            onClick = onLoadLastUsedModel,
                            enabled = !isImporting && lifecycle.state != ModelLifecycleState.LOADING,
                        ) {
                            Text(stringResource(id = R.string.ui_model_runtime_load_last_used))
                        }
                    }
                    if (lifecycle.loadedModel != null) {
                        OutlinedButton(
                            onClick = onOffloadModel,
                            enabled = !isImporting && lifecycle.state != ModelLifecycleState.OFFLOADING,
                        ) {
                            Text(stringResource(id = R.string.ui_model_runtime_offload))
                        }
                    }
                    if (lifecycle.state == ModelLifecycleState.FAILED && lifecycle.errorCode != null) {
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_runtime_failure,
                                lifecycle.errorCode.name.lowercase(),
                                lifecycle.errorDetail.orEmpty(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
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
                    Text(
                        text = stringResource(
                            id = R.string.ui_model_runtime_badge_label,
                            lifecycle.modelRuntimeBadge(model.modelId),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

        val allVersions = manifest.models
            .flatMap { it.versions }
            .distinctBy { version -> "${version.modelId}:${version.version}" }
        val versions = allVersions.filter { version ->
            val matchesSearch = searchQuery.isBlank() ||
                version.modelId.contains(searchQuery, ignoreCase = true) ||
                version.version.contains(searchQuery, ignoreCase = true)
            if (!matchesSearch) return@filter false
            if (!filterDownloaded && !filterInProgress && !filterAvailable) return@filter true
            val latestTask = downloads.firstOrNull {
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
                        val stageWarnings = latest.stageWarningChips()
                        if (stageWarnings.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                stageWarnings.forEach { warning ->
                                    AssistChip(
                                        onClick = { },
                                        enabled = false,
                                        label = {
                                            Text(warning)
                                        },
                                    )
                                }
                            }
                        }
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
        val filteredModels = snapshot.models.filter { model ->
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
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_path_origin_label,
                                stringResource(id = model.pathOriginForVersion(version.version).pathOriginLabelRes()),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val isLoadedVersion = lifecycle.loadedModel?.modelId == model.modelId &&
                            lifecycle.loadedModel?.modelVersion == version.version
                        val isLoadingVersion = lifecycle.requestedModel?.modelId == model.modelId &&
                            lifecycle.requestedModel?.modelVersion == version.version &&
                            lifecycle.state == ModelLifecycleState.LOADING
                        val isOffloadingVersion = lifecycle.requestedModel?.modelId == model.modelId &&
                            lifecycle.requestedModel?.modelVersion == version.version &&
                            lifecycle.state == ModelLifecycleState.OFFLOADING
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
                            if (isLoadedVersion) {
                                Text(
                                    text = stringResource(id = R.string.ui_model_runtime_loaded_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                OutlinedButton(
                                    onClick = onOffloadModel,
                                    enabled = !isImporting && !isOffloadingVersion,
                                ) {
                                    Text(stringResource(id = R.string.ui_model_runtime_offload))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onLoadVersion(model.modelId, version.version) },
                                    enabled = !isImporting && !isLoadingVersion &&
                                        lifecycle.state != ModelLifecycleState.OFFLOADING,
                                ) {
                                    Text(
                                        stringResource(
                                            id = if (isLoadingVersion) {
                                                R.string.ui_model_runtime_loading_action
                                            } else {
                                                R.string.ui_model_runtime_load
                                            },
                                        ),
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { onRemoveVersion(model.modelId, version.version) },
                                enabled = !version.isActive && !isImporting && !isLoadedVersion,
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
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { usedFraction },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
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
                    snapshot.storageRootLabel?.takeIf { it.isNotBlank() }?.let { rootLabel ->
                        Text(
                            text = stringResource(id = R.string.ui_model_storage_root_label, rootLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
private fun RuntimeModelLifecycleSnapshot.readableRuntimeStateLabel(): String {
    return when (state) {
        ModelLifecycleState.UNLOADED -> stringResource(id = R.string.ui_model_runtime_state_unloaded)
        ModelLifecycleState.LOADING -> stringResource(id = R.string.ui_model_runtime_state_loading)
        ModelLifecycleState.LOADED -> stringResource(id = R.string.ui_model_runtime_state_loaded)
        ModelLifecycleState.OFFLOADING -> if (queuedOffload) {
            stringResource(id = R.string.ui_model_runtime_state_offloading_queued)
        } else {
            stringResource(id = R.string.ui_model_runtime_state_offloading)
        }
        ModelLifecycleState.FAILED -> stringResource(id = R.string.ui_model_runtime_state_failed)
    }
}

@Composable
private fun RuntimeModelLifecycleSnapshot.modelRuntimeBadge(modelId: String): String {
    val loaded = loadedModel
    if (loaded != null && loaded.modelId == modelId) {
        return stringResource(id = R.string.ui_model_runtime_badge_loaded)
    }
    val requested = requestedModel
    if (requested != null && requested.modelId == modelId) {
        return when (state) {
            ModelLifecycleState.LOADING -> stringResource(id = R.string.ui_model_runtime_badge_loading)
            ModelLifecycleState.OFFLOADING -> stringResource(id = R.string.ui_model_runtime_badge_offloading)
            else -> stringResource(id = R.string.ui_model_runtime_badge_unloaded)
        }
    }
    return stringResource(id = R.string.ui_model_runtime_badge_unloaded)
}

@Composable
private fun DownloadTaskState.stageWarningChips(): List<String> {
    val chips = mutableListOf<String>()
    when (status) {
        DownloadTaskStatus.PAUSED -> chips += stringResource(id = R.string.ui_model_stage_paused)
        DownloadTaskStatus.VERIFYING -> chips += if (processingStage == DownloadProcessingStage.INSTALLING) {
            stringResource(id = R.string.ui_model_stage_installing)
        } else {
            stringResource(id = R.string.ui_model_stage_verifying_integrity)
        }
        DownloadTaskStatus.FAILED -> {
            chips += when (processingStage) {
                DownloadProcessingStage.DOWNLOADING -> stringResource(id = R.string.ui_model_stage_failure_download)
                DownloadProcessingStage.VERIFYING -> stringResource(id = R.string.ui_model_stage_failure_verification)
                DownloadProcessingStage.INSTALLING -> stringResource(id = R.string.ui_model_stage_failure_install)
                DownloadProcessingStage.CORRUPT -> stringResource(id = R.string.ui_model_stage_failure_corrupt)
            }
        }
        else -> Unit
    }
    if (failureReason == DownloadFailureReason.INSUFFICIENT_STORAGE) {
        chips += stringResource(id = R.string.ui_model_stage_low_storage)
    }
    if (failureReason == DownloadFailureReason.TIMEOUT) {
        chips += stringResource(id = R.string.ui_model_stage_timeout)
    }
    return chips
}

@Composable
private fun DownloadTaskState.failureReasonMessage(version: ModelDistributionVersion): String {
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

private const val BYTES_PER_GIB: Double = 1024.0 * 1024.0 * 1024.0

internal fun downloadVersionItemKey(modelId: String, version: String): String {
    return "download:$modelId:$version"
}

internal fun installedVersionItemKey(modelId: String, version: String): String {
    return "installed:$modelId:$version"
}
