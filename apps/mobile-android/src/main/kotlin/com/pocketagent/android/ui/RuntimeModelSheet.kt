package com.pocketagent.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.activeOrRequestedModel
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog

@Composable
internal fun RuntimeModelSheet(
    state: RuntimeModelUiState,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    onLoadVersion: (String, String) -> Unit,
    onLoadLastUsedModel: () -> Unit,
    onOffloadModel: () -> Unit,
    onRefreshRuntime: () -> Unit,
    onOpenModelLibrary: () -> Unit,
    onClose: () -> Unit,
) {
    val installedModels = state.snapshot.models.filter { model ->
        model.installedVersions.isNotEmpty()
    }
    val routingModeLabel = when (routingMode) {
        RoutingMode.AUTO -> stringResource(id = R.string.ui_routing_mode_auto)
        else -> routingMode.name
    }
    val loadedModel = modelLoadingState.loadedModel
    val activeOrRequestedModel = modelLoadingState.activeOrRequestedModel()
    val routingTargetModelId = ModelCatalog.modelIdForRoutingMode(routingMode)
    val loadedDiffersFromRouting = loadedModel?.modelId?.let { loadedModelId ->
        routingTargetModelId != null && routingTargetModelId != loadedModelId
    } == true
    val busy = modelLoadingState is ModelLoadingState.Loading || modelLoadingState is ModelLoadingState.Offloading

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("runtime_model_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = R.string.ui_runtime_model_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    modifier = Modifier.testTag("open_model_library_button"),
                    onClick = onOpenModelLibrary,
                ) {
                    Text(stringResource(id = R.string.ui_open_model_library))
                }
            }
        }
        item {
            Text(
                text = stringResource(id = R.string.ui_runtime_model_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ActiveRuntimeCard(
                modelLoadingState = modelLoadingState,
                routingModeLabel = routingModeLabel,
                loadedDiffersFromRouting = loadedDiffersFromRouting,
                isImporting = state.isImporting,
                onLoadLastUsedModel = onLoadLastUsedModel,
                onLoadVersion = onLoadVersion,
                onOffloadModel = onOffloadModel,
                onRefreshRuntime = onRefreshRuntime,
            )
        }

        if (installedModels.isEmpty()) {
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(id = R.string.ui_runtime_model_empty_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(id = R.string.ui_runtime_model_empty_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onOpenModelLibrary) {
                            Text(stringResource(id = R.string.ui_open_model_library))
                        }
                    }
                }
            }
        } else {
            item { HorizontalDivider() }
            item {
                Text(
                    text = stringResource(id = R.string.ui_runtime_model_installed_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Text(
                    text = stringResource(id = R.string.ui_runtime_model_installed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    installedModels.forEach { model ->
                        items(
                            model.installedVersions,
                            key = { version ->
                                installedVersionItemKey(modelId = model.modelId, version = version.version)
                            },
                        ) { version ->
                            val isLoadedVersion = loadedModel?.modelId == model.modelId &&
                                loadedModel.modelVersion == version.version
                            val isRequestedVersion = activeOrRequestedModel?.modelId == model.modelId &&
                                activeOrRequestedModel.modelVersion == version.version &&
                                modelLoadingState is ModelLoadingState.Loading
                            RuntimeVersionCard(
                                modelDisplayName = model.displayName,
                                modelId = model.modelId,
                                version = version.version,
                                isActive = version.isActive,
                                isLoaded = isLoadedVersion,
                                isRequested = isRequestedVersion,
                                isBusy = busy,
                                isImporting = state.isImporting,
                                onLoadVersion = onLoadVersion,
                            )
                        }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onOpenModelLibrary) {
                    Text(stringResource(id = R.string.ui_open_model_library))
                }
                Button(onClick = onClose) {
                    Text(stringResource(id = R.string.ui_close))
                }
            }
        }
    }
}

@Composable
private fun ActiveRuntimeCard(
    modelLoadingState: ModelLoadingState,
    routingModeLabel: String,
    loadedDiffersFromRouting: Boolean,
    isImporting: Boolean,
    onLoadLastUsedModel: () -> Unit,
    onLoadVersion: (String, String) -> Unit,
    onOffloadModel: () -> Unit,
    onRefreshRuntime: () -> Unit,
) {
    val currentModel = modelLoadingState.activeOrRequestedModel()
    val canLoadLastUsed = modelLoadingState.loadedModel == null &&
        modelLoadingState.lastUsedModel != null &&
        modelLoadingState !is ModelLoadingState.Loading &&
        modelLoadingState !is ModelLoadingState.Offloading
    val canOffload = modelLoadingState.loadedModel != null &&
        modelLoadingState !is ModelLoadingState.Offloading
    val isError = modelLoadingState is ModelLoadingState.Error
    val canRetry = isError && (modelLoadingState as ModelLoadingState.Error).requestedModel != null

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(id = R.string.ui_model_runtime_active_section_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModelStatusDot(color = modelLoadingState.statusColor())
                        Text(
                            text = modelLoadingState.statusHeadline(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = modelLoadingState.statusColor(),
                        )
                    }
                }
                Text(
                    text = routingModeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (currentModel != null && currentModel.modelId.isNotBlank()) {
                Text(
                    text = buildString {
                        append(currentModel.modelId)
                        currentModel.modelVersion?.takeIf { it.isNotBlank() }?.let { version ->
                            append(" \u2022 ")
                            append(version)
                        }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    text = stringResource(id = R.string.ui_runtime_model_none_loaded),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = modelLoadingState.lastUsedModel != null &&
                    modelLoadingState !is ModelLoadingState.Loaded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                modelLoadingState.lastUsedModel?.let { lastUsed ->
                    Text(
                        text = stringResource(
                            id = R.string.ui_model_runtime_last_used_label,
                            lastUsed.modelId,
                            lastUsed.modelVersion.orEmpty().ifBlank { "-" },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (loadedDiffersFromRouting) {
                Text(
                    text = stringResource(id = R.string.ui_model_runtime_routing_mismatch_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (modelLoadingState) {
                is ModelLoadingState.Loading -> {
                    val progress = modelLoadingState.progress?.coerceIn(0f, 1f)
                    if (progress != null && progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = buildString {
                            append(modelLoadingState.stage)
                            if (progress != null && progress > 0f) {
                                append(" ")
                                append((progress * 100).toInt())
                                append("%")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ModelLoadingState.Offloading -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (modelLoadingState.queued) {
                            stringResource(id = R.string.ui_model_runtime_offload_queued)
                        } else {
                            stringResource(id = R.string.ui_model_runtime_offload_releasing)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ModelLoadingState.Error -> {
                    Text(
                        text = modelLoadingState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = errorSuggestion(modelLoadingState.code),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canRetry) {
                    val error = modelLoadingState as ModelLoadingState.Error
                    val requested = error.requestedModel!!
                    Button(
                        onClick = {
                            onLoadVersion(
                                requested.modelId,
                                requested.modelVersion.orEmpty(),
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                        enabled = !isImporting,
                    ) {
                        Text(stringResource(id = R.string.ui_model_runtime_retry_load))
                    }
                }
                if (canLoadLastUsed) {
                    OutlinedButton(
                        onClick = onLoadLastUsedModel,
                        enabled = !isImporting,
                    ) {
                        Text(stringResource(id = R.string.ui_model_runtime_load_last_used))
                    }
                }
                if (canOffload) {
                    OutlinedButton(
                        onClick = onOffloadModel,
                        enabled = !isImporting,
                    ) {
                        Text(stringResource(id = R.string.ui_model_runtime_offload))
                    }
                }
                Button(
                    onClick = onRefreshRuntime,
                    enabled = !isImporting && modelLoadingState !is ModelLoadingState.Loading && modelLoadingState !is ModelLoadingState.Offloading,
                ) {
                    Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                }
            }
        }
    }
}

@Composable
private fun RuntimeVersionCard(
    modelDisplayName: String,
    modelId: String,
    version: String,
    isActive: Boolean,
    isLoaded: Boolean,
    isRequested: Boolean,
    isBusy: Boolean,
    isImporting: Boolean,
    onLoadVersion: (String, String) -> Unit,
) {
    Card(
        modifier = Modifier.widthIn(min = 180.dp, max = 240.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelStatusDot(
                    color = when {
                        isLoaded -> MaterialTheme.colorScheme.primary
                        isRequested -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
                Text(
                    text = stringResource(id = R.string.ui_model_installed_version_row, modelDisplayName, version),
                    modifier = Modifier.semantics {
                        contentDescription = modelInstalledVersionLabel(modelId, version)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    Text(
                        text = stringResource(id = R.string.ui_model_current_active_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isLoaded) {
                    Text(
                        text = stringResource(id = R.string.ui_model_runtime_loaded_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (isRequested) {
                    Text(
                        text = stringResource(id = R.string.ui_model_runtime_loading_action),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (!isLoaded) {
                OutlinedButton(
                    onClick = { onLoadVersion(modelId, version) },
                    enabled = !isImporting && !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.ui_model_runtime_load_now))
                }
            }
        }
    }
}

@Composable
private fun ModelStatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(MaterialTheme.shapes.small)
            .background(color),
    )
}

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

@Composable
private fun errorSuggestion(errorCode: String?): String {
    return when (errorCode) {
        "OUT_OF_MEMORY" -> stringResource(id = R.string.ui_model_runtime_error_suggestion_oom)
        "MODEL_FILE_UNAVAILABLE" -> stringResource(id = R.string.ui_model_runtime_error_suggestion_file)
        else -> stringResource(id = R.string.ui_model_runtime_error_suggestion_generic)
    }
}
