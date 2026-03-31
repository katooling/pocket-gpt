@file:OptIn(ExperimentalLayoutApi::class)

package com.pocketagent.android.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.pocketagent.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.ui.components.SectionHeader
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.activeOrRequestedModel
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.core.RoutingMode

@Composable
internal fun ModelSheet(
    libraryState: ModelLibraryUiState,
    runtimeState: RuntimeModelUiState,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    onEvent: (ModelSheetEvent) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var pendingRemoveVersion by remember { mutableStateOf<Pair<String, String>?>(null) }
    val activeModel = modelLoadingState.activeOrRequestedModel()
    val busy = modelLoadingState is ModelLoadingState.Loading || modelLoadingState is ModelLoadingState.Offloading
    val installedVersions by remember(libraryState, searchQuery) {
        derivedStateOf {
            libraryState.snapshot.models.flatMap { model ->
                model.installedVersions.map { version -> model to version }
            }.filter { (model, version) ->
                matchesModelSearch(
                    searchQuery = searchQuery,
                    modelId = model.modelId,
                    displayName = model.displayName,
                    version = version.version,
                )
            }
        }
    }
    val installedKeys by remember(installedVersions) {
        derivedStateOf {
            installedVersions
                .map { (model, version) -> versionIdentityKey(model.modelId, version.version) }
                .toSet()
        }
    }
    val availableVersions by remember(libraryState, searchQuery, installedKeys) {
        derivedStateOf {
            libraryState.manifest.models.flatMap { model ->
                model.versions.map { version -> model.displayName to version }
            }.filter { (displayName, version) ->
                versionIdentityKey(version.modelId, version.version) !in installedKeys &&
                    matchesModelSearch(
                        searchQuery = searchQuery,
                        modelId = version.modelId,
                        displayName = displayName,
                        version = version.version,
                    )
            }
        }
    }
    val downloadTasksByKey by remember(libraryState) {
        derivedStateOf {
            libraryState.downloads.associateBy { task ->
                versionIdentityKey(task.modelId, task.version)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PocketAgentDimensions.sheetHorizontalPadding)
            .testTag("unified_model_sheet"),
        verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.screenPadding),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onEvent(ModelSheetEvent.RefreshAll) }) {
                    Text(stringResource(id = R.string.ui_refresh))
                }
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(id = R.string.ui_search_models)) },
            )
        }
        libraryState.statusMessage?.takeIf { message -> message.isNotBlank() }?.let { message ->
            item {
                StatusMessageCard(message = message)
            }
        }
        item {
            ActiveModelSection(
                modelLoadingState = modelLoadingState,
                routingMode = routingMode,
                onRetryLoad = { model -> onEvent(ModelSheetEvent.RetryLoad(model.modelId, model.modelVersion)) },
                onLoadLastUsedModel = { onEvent(ModelSheetEvent.LoadLastUsedModel) },
                onOffloadModel = { onEvent(ModelSheetEvent.OffloadModel) },
            )
        }
        item { HorizontalDivider() }
        item {
            SectionHeader(
                title = stringResource(id = R.string.ui_downloaded_models),
                subtitle = stringResource(id = R.string.ui_downloaded_models_subtitle),
            )
        }
        if (installedVersions.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(id = R.string.ui_no_downloaded_models_title),
                    body = stringResource(id = R.string.ui_no_downloaded_models_body),
                )
            }
        } else {
            items(
                installedVersions,
                key = { (model, version) -> installedVersionItemKey(model.modelId, version.version) },
            ) { (model, version) ->
                DownloadedModelCard(
                    model = model,
                    version = version,
                    defaultGetReadyModelId = libraryState.defaultGetReadyModelId,
                    activeModel = activeModel,
                    loadedModel = modelLoadingState.loadedModel,
                    busy = busy,
                    onImportModel = { modelId -> onEvent(ModelSheetEvent.ImportModel(modelId)) },
                    onSetDefaultVersion = { modelId, ver -> onEvent(ModelSheetEvent.SetDefaultVersion(modelId, ver)) },
                    onLoadVersion = { modelId, ver -> onEvent(ModelSheetEvent.LoadVersion(modelId, ver)) },
                    onRemoveVersion = { modelId, ver -> pendingRemoveVersion = modelId to ver },
                )
            }
        }
        item { HorizontalDivider() }
        item {
            SectionHeader(
                title = stringResource(id = R.string.ui_available_models),
                subtitle = stringResource(id = R.string.ui_available_models_subtitle),
            )
        }
        if (availableVersions.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(id = R.string.ui_catalog_up_to_date_title),
                    body = stringResource(id = R.string.ui_catalog_up_to_date_body),
                )
            }
        } else {
            items(
                availableVersions,
                key = { (_, version) -> downloadVersionItemKey(version.modelId, version.version) },
            ) { (displayName, version) ->
                AvailableModelCard(
                    displayName = displayName,
                    version = version,
                    task = downloadTasksByKey[versionIdentityKey(version.modelId, version.version)],
                    isImporting = runtimeState.isImporting,
                    onImportModel = { modelId -> onEvent(ModelSheetEvent.ImportModel(modelId)) },
                    onDownloadVersion = { ver -> onEvent(ModelSheetEvent.DownloadVersion(ver)) },
                    onPauseDownload = { taskId -> onEvent(ModelSheetEvent.PauseDownload(taskId)) },
                    onResumeDownload = { taskId -> onEvent(ModelSheetEvent.ResumeDownload(taskId)) },
                    onRetryDownload = { taskId -> onEvent(ModelSheetEvent.RetryDownload(taskId)) },
                    onCancelDownload = { taskId -> onEvent(ModelSheetEvent.CancelDownload(taskId)) },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { onEvent(ModelSheetEvent.Close) }) {
                    Text(stringResource(id = R.string.ui_close))
                }
            }
        }
    }
    pendingRemoveVersion?.let { (modelId, version) ->
        val model = libraryState.snapshot.models.firstOrNull { installedModel -> installedModel.modelId == modelId }
        val targetVersion = model?.installedVersions?.firstOrNull { installedVersion -> installedVersion.version == version }
        val removePlan = if (model != null && targetVersion != null) {
            resolveRemoveVersionPlan(
                model = model,
                version = targetVersion,
                loadedModel = modelLoadingState.loadedModel,
            )
        } else {
            null
        }
        AlertDialog(
            onDismissRequest = { pendingRemoveVersion = null },
            title = { Text(stringResource(id = R.string.ui_remove_model_title)) },
            text = {
                Text(
                    text = when {
                        removePlan?.isBlockedByActiveSelection == true ->
                            stringResource(id = R.string.ui_remove_model_body_active_blocked, version)
                        removePlan?.requiresOffload == true && removePlan.requiresClearingActiveSelection ->
                            stringResource(id = R.string.ui_remove_model_body_loaded_only_active, version)
                        removePlan?.requiresOffload == true ->
                            stringResource(id = R.string.ui_remove_model_body_loaded, version)
                        removePlan?.requiresClearingActiveSelection == true ->
                            stringResource(id = R.string.ui_remove_model_body_only_active, version)
                        else ->
                            stringResource(id = R.string.ui_remove_model_body, version)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEvent(ModelSheetEvent.RemoveVersion(modelId, version))
                        pendingRemoveVersion = null
                    },
                    enabled = removePlan?.isBlockedByActiveSelection != true,
                ) {
                    Text(stringResource(id = R.string.ui_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveVersion = null }) {
                    Text(stringResource(id = R.string.ui_cancel_button))
                }
            },
        )
    }
}

@Composable
private fun StatusMessageCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_sheet_status_message"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ActiveModelSection(
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    onRetryLoad: (com.pocketagent.runtime.RuntimeLoadedModel) -> Unit,
    onLoadLastUsedModel: () -> Unit,
    onOffloadModel: () -> Unit,
) {
    val currentModel = modelLoadingState.activeOrRequestedModel()
    val canLoadLastUsed = modelLoadingState.loadedModel == null &&
        modelLoadingState.lastUsedModel != null &&
        modelLoadingState !is ModelLoadingState.Error &&
        modelLoadingState !is ModelLoadingState.Loading &&
        modelLoadingState !is ModelLoadingState.Offloading
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Text(stringResource(id = R.string.ui_active_model), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            StatusRow(
                color = modelLoadingState.statusColor(),
                label = modelLoadingState.statusHeadline(),
            )
            Text(
                text = currentModel?.let { loaded ->
                    buildString {
                        append(loaded.modelId)
                        loaded.modelVersion?.takeIf { it.isNotBlank() }?.let { version ->
                            append(" • ")
                            append(version)
                        }
                    }
                } ?: stringResource(id = R.string.ui_nothing_loaded),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.ui_routing_mode_label, routingMode.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (modelLoadingState) {
                is ModelLoadingState.Loading -> {
                    val progress = modelLoadingState.progress
                    if (progress != null && progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = modelLoadingState.stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ModelLoadingState.Offloading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(id = if (modelLoadingState.queued) R.string.ui_unload_queued else R.string.ui_releasing_runtime_memory),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ModelLoadingState.Error -> {
                    Text(
                        text = modelLoadingState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    currentModel?.let { retryModel ->
                        OutlinedButton(onClick = { onRetryLoad(retryModel) }) {
                            Text(stringResource(id = R.string.ui_model_runtime_retry_load))
                        }
                    }
                }

                is ModelLoadingState.Loaded -> {
                    modelLoadingState.detail?.takeIf { detail -> detail.isNotBlank() }?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> Unit
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            ) {
                if (canLoadLastUsed) {
                    OutlinedButton(onClick = onLoadLastUsedModel) {
                        Text(stringResource(id = R.string.ui_load_last_used))
                    }
                }
                if (modelLoadingState.loadedModel != null) {
                    OutlinedButton(onClick = onOffloadModel) {
                        Text(stringResource(id = R.string.ui_unload))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedModelCard(
    model: ProvisionedModelState,
    version: ModelVersionDescriptor,
    defaultGetReadyModelId: String?,
    activeModel: com.pocketagent.runtime.RuntimeLoadedModel?,
    loadedModel: com.pocketagent.runtime.RuntimeLoadedModel?,
    busy: Boolean,
    onImportModel: (String) -> Unit,
    onSetDefaultVersion: (String, String) -> Unit,
    onLoadVersion: (String, String) -> Unit,
    onRemoveVersion: (String, String) -> Unit,
) {
    val badge = resolveDownloadedModelBadge(
        model = model,
        version = version,
        defaultGetReadyModelId = defaultGetReadyModelId,
        activeModel = activeModel,
        loadedModel = loadedModel,
    )
    val isLoaded = badge == DownloadedModelBadge.LOADED
    val statusColor = when (badge) {
        DownloadedModelBadge.LOADED -> MaterialTheme.colorScheme.primary
        DownloadedModelBadge.SWITCHING -> MaterialTheme.colorScheme.tertiary
        DownloadedModelBadge.DEFAULT,
        DownloadedModelBadge.ACTIVE,
        -> MaterialTheme.colorScheme.secondary
        DownloadedModelBadge.READY -> MaterialTheme.colorScheme.outline
    }
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2),
                ) {
                    Text(model.displayName, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = stringResource(
                            id = R.string.ui_model_installed_version_row,
                            model.modelId,
                            version.version,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusRow(
                    color = statusColor,
                    label = when (badge) {
                        DownloadedModelBadge.LOADED -> stringResource(id = R.string.ui_loaded)
                        DownloadedModelBadge.SWITCHING -> stringResource(id = R.string.ui_switching)
                        DownloadedModelBadge.DEFAULT -> stringResource(id = R.string.ui_default)
                        DownloadedModelBadge.ACTIVE -> stringResource(id = R.string.ui_active)
                        DownloadedModelBadge.READY -> stringResource(id = R.string.ui_ready)
                    },
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            ) {
                Button(
                    onClick = { onLoadVersion(model.modelId, version.version) },
                    enabled = !busy && !isLoaded,
                ) {
                    Text(stringResource(id = if (isLoaded) R.string.ui_loaded else R.string.ui_load))
                }
                OutlinedButton(
                    onClick = { onSetDefaultVersion(model.modelId, version.version) },
                    enabled = !version.isActive,
                ) {
                    Text(stringResource(id = if (version.isActive) R.string.ui_active else R.string.ui_set_active))
                }
                OutlinedButton(onClick = { onImportModel(model.modelId) }) {
                    Text(stringResource(id = if (model.isProvisioned) R.string.ui_replace_file else R.string.ui_import))
                }
                OutlinedButton(onClick = { onRemoveVersion(model.modelId, version.version) }) {
                    Text(stringResource(id = R.string.ui_remove))
                }
            }
        }
    }
}

@Composable
private fun AvailableModelCard(
    displayName: String,
    version: ModelDistributionVersion,
    task: DownloadTaskState?,
    isImporting: Boolean,
    onImportModel: (String) -> Unit,
    onDownloadVersion: (ModelDistributionVersion) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
) {
    val context = LocalContext.current
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Text(displayName, style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(
                    id = R.string.ui_model_download_version_label,
                    version.modelId,
                    version.version,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    id = R.string.ui_model_download_expected_size,
                    Formatter.formatShortFileSize(context, version.fileSizeBytes.coerceAtLeast(0L)),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task != null) {
                val progress = (task.progressPercent / 100f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        id = R.string.ui_model_download_state,
                        task.readableStateNameLocalized(),
                        task.progressPercent,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                task.stageWarningChips()
                if (task.status == DownloadTaskStatus.FAILED || task.status == DownloadTaskStatus.CANCELLED) {
                    Text(
                        text = task.failureReasonMessage(version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
            ) {
                when (task?.status) {
                    DownloadTaskStatus.DOWNLOADING,
                    DownloadTaskStatus.QUEUED,
                    DownloadTaskStatus.VERIFYING,
                    -> {
                        OutlinedButton(onClick = { onPauseDownload(task.taskId) }) {
                            Text(stringResource(id = R.string.ui_pause))
                        }
                        OutlinedButton(onClick = { onCancelDownload(task.taskId) }) {
                            Text(stringResource(id = R.string.ui_cancel_button))
                        }
                    }

                    DownloadTaskStatus.PAUSED -> {
                        Button(onClick = { onResumeDownload(task.taskId) }) {
                            Text(stringResource(id = R.string.ui_resume))
                        }
                        OutlinedButton(onClick = { onCancelDownload(task.taskId) }) {
                            Text(stringResource(id = R.string.ui_cancel_button))
                        }
                    }

                    DownloadTaskStatus.FAILED,
                    DownloadTaskStatus.CANCELLED,
                    -> {
                        Button(onClick = { onRetryDownload(task.taskId) }) {
                            Text(stringResource(id = R.string.ui_retry))
                        }
                    }

                    else -> {
                        Button(onClick = { onDownloadVersion(version) }) {
                            Text(stringResource(id = R.string.ui_download))
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onImportModel(version.modelId) },
                    enabled = !isImporting,
                ) {
                    Text(stringResource(id = R.string.ui_import))
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PocketAgentDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(
    color: Color,
    label: String,
) {
    val statusDescription = stringResource(
        id = R.string.cd_model_status_indicator,
        label,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            color = color,
            statusDescription = statusDescription,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun StatusDot(color: Color, statusDescription: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(PocketAgentDimensions.statusDotSize)
            .clip(MaterialTheme.shapes.small)
            .background(color)
            .semantics {
                contentDescription = statusDescription
            },
    )
}

private fun matchesModelSearch(
    searchQuery: String,
    modelId: String,
    displayName: String,
    version: String,
): Boolean {
    if (searchQuery.isBlank()) {
        return true
    }
    return modelId.contains(searchQuery, ignoreCase = true) ||
        displayName.contains(searchQuery, ignoreCase = true) ||
        version.contains(searchQuery, ignoreCase = true)
}

private fun versionIdentityKey(modelId: String, version: String): String = "$modelId::$version"

@Composable
internal fun ModelLoadingState.statusHeadline(): String {
    return when (this) {
        is ModelLoadingState.Idle -> stringResource(id = R.string.ui_model_runtime_state_unloaded)
        is ModelLoadingState.Loading -> stage
        is ModelLoadingState.Loaded -> stringResource(id = R.string.ui_model_runtime_state_loaded)
        is ModelLoadingState.Offloading -> stringResource(id = R.string.ui_model_runtime_state_offloading)
        is ModelLoadingState.Error -> stringResource(id = R.string.ui_model_runtime_state_failed)
    }
}

@Composable
internal fun ModelLoadingState.statusColor(): Color {
    return when (this) {
        is ModelLoadingState.Idle -> MaterialTheme.colorScheme.outline
        is ModelLoadingState.Loading -> MaterialTheme.colorScheme.tertiary
        is ModelLoadingState.Loaded -> MaterialTheme.colorScheme.primary
        is ModelLoadingState.Offloading -> MaterialTheme.colorScheme.secondary
        is ModelLoadingState.Error -> MaterialTheme.colorScheme.error
    }
}
