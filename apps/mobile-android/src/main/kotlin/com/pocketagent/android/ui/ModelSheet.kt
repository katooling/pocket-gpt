@file:OptIn(ExperimentalLayoutApi::class)

package com.pocketagent.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.runtime.ProvisionedModelState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.activeOrRequestedModel
import com.pocketagent.core.RoutingMode

@Composable
internal fun ModelSheet(
    libraryState: ModelLibraryUiState,
    runtimeState: RuntimeModelUiState,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    onImportModel: (String) -> Unit,
    onDownloadVersion: (ModelDistributionVersion) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onSetDefaultVersion: (String, String) -> Unit,
    onLoadVersion: (String, String) -> Unit,
    onLoadLastUsedModel: () -> Unit,
    onOffloadModel: () -> Unit,
    onRemoveVersion: (String, String) -> Unit,
    onRefreshAll: () -> Unit,
    onClose: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val activeModel = modelLoadingState.activeOrRequestedModel()
    val busy = modelLoadingState is ModelLoadingState.Loading || modelLoadingState is ModelLoadingState.Offloading
    val installedVersions = libraryState.snapshot.models.flatMap { model ->
        model.installedVersions.map { version -> model to version }
    }.filter { (model, version) ->
        matchesModelSearch(
            searchQuery = searchQuery,
            modelId = model.modelId,
            displayName = model.displayName,
            version = version.version,
        )
    }
    val installedKeys = installedVersions
        .map { (model, version) -> versionKey(model.modelId, version.version) }
        .toSet()
    val availableVersions = libraryState.manifest.models.flatMap { model ->
        model.versions.map { version -> model.displayName to version }
    }.filter { (_, version) ->
        versionKey(version.modelId, version.version) !in installedKeys &&
            matchesModelSearch(
                searchQuery = searchQuery,
                modelId = version.modelId,
                displayName = version.modelId,
                version = version.version,
            )
    }
    val downloadTasksByKey = libraryState.downloads.associateBy { task ->
        versionKey(task.modelId, task.version)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("unified_model_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Models",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Load, set defaults, import local files, and manage downloads from one place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onRefreshAll) {
                    Text("Refresh")
                }
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search models") },
            )
        }
        item {
            ActiveModelSection(
                modelLoadingState = modelLoadingState,
                routingMode = routingMode,
                onLoadLastUsedModel = onLoadLastUsedModel,
                onOffloadModel = onOffloadModel,
            )
        }
        item { HorizontalDivider() }
        item {
            SectionTitle(
                title = "Downloaded models",
                subtitle = "One tap to switch. \"Set as default\" controls app startup and routing follow-through.",
            )
        }
        if (installedVersions.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No downloaded models yet",
                    body = "Import a local GGUF or download one from the catalog below.",
                )
            }
        } else {
            items(installedVersions, key = { (model, version) -> versionKey(model.modelId, version.version) }) { (model, version) ->
                DownloadedModelCard(
                    model = model,
                    version = version,
                    activeModel = activeModel,
                    loadedModel = modelLoadingState.loadedModel,
                    busy = busy,
                    onImportModel = onImportModel,
                    onSetDefaultVersion = onSetDefaultVersion,
                    onLoadVersion = onLoadVersion,
                    onRemoveVersion = onRemoveVersion,
                )
            }
        }
        item { HorizontalDivider() }
        item {
            SectionTitle(
                title = "Available models",
                subtitle = "Downloads stream progress inline and switch to loadable cards as soon as the model is installed.",
            )
        }
        if (availableVersions.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "Catalog is up to date",
                    body = "Everything in the current manifest is already installed or filtered out.",
                )
            }
        } else {
            items(availableVersions, key = { (_, version) -> versionKey(version.modelId, version.version) }) { (displayName, version) ->
                AvailableModelCard(
                    displayName = displayName,
                    version = version,
                    task = downloadTasksByKey[versionKey(version.modelId, version.version)],
                    isImporting = runtimeState.isImporting,
                    onImportModel = onImportModel,
                    onDownloadVersion = onDownloadVersion,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onRetryDownload = onRetryDownload,
                    onCancelDownload = onCancelDownload,
                )
            }
        }
        libraryState.statusMessage?.let { message ->
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
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onClose) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun ActiveModelSection(
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    onLoadLastUsedModel: () -> Unit,
    onOffloadModel: () -> Unit,
) {
    val currentModel = modelLoadingState.activeOrRequestedModel()
    val canLoadLastUsed = modelLoadingState.loadedModel == null &&
        modelLoadingState.lastUsedModel != null &&
        modelLoadingState !is ModelLoadingState.Loading &&
        modelLoadingState !is ModelLoadingState.Offloading
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Active model", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
                } ?: "Nothing is loaded",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Routing mode: $routingMode",
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
                        text = if (modelLoadingState.queued) "Unload queued..." else "Releasing runtime memory...",
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
                }

                else -> Unit
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canLoadLastUsed) {
                    OutlinedButton(onClick = onLoadLastUsedModel) {
                        Text("Load last used")
                    }
                }
                if (modelLoadingState.loadedModel != null) {
                    OutlinedButton(onClick = onOffloadModel) {
                        Text("Unload")
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
    activeModel: com.pocketagent.runtime.RuntimeLoadedModel?,
    loadedModel: com.pocketagent.runtime.RuntimeLoadedModel?,
    busy: Boolean,
    onImportModel: (String) -> Unit,
    onSetDefaultVersion: (String, String) -> Unit,
    onLoadVersion: (String, String) -> Unit,
    onRemoveVersion: (String, String) -> Unit,
) {
    val isLoaded = loadedModel?.modelId == model.modelId && loadedModel.modelVersion == version.version
    val isRequested = activeModel?.modelId == model.modelId && activeModel.modelVersion == version.version && !isLoaded
    val statusColor = when {
        isLoaded -> MaterialTheme.colorScheme.primary
        isRequested -> MaterialTheme.colorScheme.tertiary
        version.isActive -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.displayName, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "${model.modelId} • ${version.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusRow(
                    color = statusColor,
                    label = when {
                        isLoaded -> "Loaded"
                        isRequested -> "Switching"
                        version.isActive -> "Default"
                        else -> "Ready"
                    },
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onLoadVersion(model.modelId, version.version) },
                    enabled = !busy && !isLoaded,
                ) {
                    Text(if (isLoaded) "Loaded" else "Load")
                }
                OutlinedButton(
                    onClick = { onSetDefaultVersion(model.modelId, version.version) },
                    enabled = !version.isActive,
                ) {
                    Text(if (version.isActive) "Default" else "Set as default")
                }
                OutlinedButton(onClick = { onImportModel(model.modelId) }) {
                    Text(if (model.isProvisioned) "Replace file" else "Import")
                }
                OutlinedButton(onClick = { onRemoveVersion(model.modelId, version.version) }) {
                    Text("Remove")
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
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(displayName, style = MaterialTheme.typography.labelLarge)
            Text(
                text = "${version.modelId} • ${version.version}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = version.fileSizeBytes.formatAsGiB(),
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
                    text = "${task.status.name.lowercase().replace('_', ' ')} • ${task.progressPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (task?.status) {
                    DownloadTaskStatus.DOWNLOADING,
                    DownloadTaskStatus.QUEUED,
                    DownloadTaskStatus.VERIFYING,
                    -> {
                        OutlinedButton(onClick = { onPauseDownload(task.taskId) }) {
                            Text("Pause")
                        }
                        OutlinedButton(onClick = { onCancelDownload(task.taskId) }) {
                            Text("Cancel")
                        }
                    }

                    DownloadTaskStatus.PAUSED -> {
                        Button(onClick = { onResumeDownload(task.taskId) }) {
                            Text("Resume")
                        }
                        OutlinedButton(onClick = { onCancelDownload(task.taskId) }) {
                            Text("Cancel")
                        }
                    }

                    DownloadTaskStatus.FAILED,
                    DownloadTaskStatus.CANCELLED,
                    -> {
                        Button(onClick = { onRetryDownload(task.taskId) }) {
                            Text("Retry")
                        }
                    }

                    else -> {
                        Button(onClick = { onDownloadVersion(version) }) {
                            Text("Download")
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onImportModel(version.modelId) },
                    enabled = !isImporting,
                ) {
                    Text("Import")
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
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
private fun SectionTitle(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(
    color: Color,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(10.dp)
            .clip(MaterialTheme.shapes.small)
            .background(color),
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

private fun versionKey(modelId: String, version: String): String = "$modelId::$version"
