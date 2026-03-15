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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.runtime.errorCodeName
import com.pocketagent.android.runtime.isFailed
import com.pocketagent.android.runtime.isLoading
import com.pocketagent.android.runtime.isOffloading

@Composable
internal fun RuntimeModelSheet(
    state: RuntimeModelUiState,
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
                            state.lifecycle.readableRuntimeStateLabel(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.lifecycle.loadedModel?.let { loaded ->
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
                    state.lifecycle.requestedModel?.let { requested ->
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_runtime_requested_version_label,
                                requested.modelId,
                                requested.modelVersion.orEmpty(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.lifecycle.lastUsedModel?.let { lastUsed ->
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_runtime_last_used_label,
                                lastUsed.modelId,
                                lastUsed.modelVersion.orEmpty(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.lifecycle.isLoading() && state.lifecycle.loadingProgress != null) {
                        val progress = state.lifecycle.loadingProgress.coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = buildString {
                                append(state.lifecycle.loadingDetail.orEmpty().ifBlank { "Loading model..." })
                                append(" ")
                                append((progress * 100).toInt())
                                append("%")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!state.lifecycle.loadingDetail.isNullOrBlank() &&
                        state.lifecycle.isLoading()
                    ) {
                        Text(
                            text = state.lifecycle.loadingDetail.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.lifecycle.isFailed() && state.lifecycle.errorCode != null) {
                        Text(
                            text = stringResource(
                                id = R.string.ui_model_runtime_failure,
                                state.lifecycle.errorCodeName()?.lowercase().orEmpty(),
                                state.lifecycle.errorDetail.orEmpty(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.lifecycle.loadedModel == null && state.lifecycle.lastUsedModel != null) {
                            OutlinedButton(
                                onClick = onLoadLastUsedModel,
                                enabled = !state.isImporting && !state.lifecycle.isLoading(),
                            ) {
                                Text(stringResource(id = R.string.ui_model_runtime_load_last_used))
                            }
                        }
                        if (state.lifecycle.loadedModel != null) {
                            OutlinedButton(
                                onClick = onOffloadModel,
                                enabled = !state.isImporting && !state.lifecycle.isOffloading(),
                            ) {
                                Text(stringResource(id = R.string.ui_model_runtime_offload))
                            }
                        }
                        Button(
                            onClick = onRefreshRuntime,
                            enabled = !state.isImporting,
                        ) {
                            Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                        }
                    }
                }
            }
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
            installedModels.forEach { model ->
                items(
                    model.installedVersions,
                    key = { version ->
                        installedVersionItemKey(modelId = model.modelId, version = version.version)
                    },
                ) { version ->
                    val isLoadedVersion = state.lifecycle.loadedModel?.modelId == model.modelId &&
                        state.lifecycle.loadedModel?.modelVersion == version.version
                    val isLoadingVersion = state.lifecycle.requestedModel?.modelId == model.modelId &&
                        state.lifecycle.requestedModel?.modelVersion == version.version &&
                        state.lifecycle.isLoading()
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
                                    id = R.string.ui_model_installed_added_at,
                                    version.importedAtEpochMs.formatAsTimestamp(),
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
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (version.isActive) {
                                    Text(
                                        text = stringResource(id = R.string.ui_model_current_active_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (isLoadedVersion) {
                                    Text(
                                        text = stringResource(id = R.string.ui_model_runtime_loaded_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = { onLoadVersion(model.modelId, version.version) },
                                        enabled = !state.isImporting &&
                                            !isLoadingVersion &&
                                            !state.lifecycle.isOffloading(),
                                    ) {
                                        Text(
                                            stringResource(
                                                id = if (isLoadingVersion) {
                                                    R.string.ui_model_runtime_loading_action
                                                } else {
                                                    R.string.ui_model_runtime_load_now
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
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
