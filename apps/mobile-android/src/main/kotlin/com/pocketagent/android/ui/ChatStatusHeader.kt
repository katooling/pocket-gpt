package com.pocketagent.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import com.pocketagent.android.ui.state.StartupProbeState

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun OfflineAndStatusHeader(
    state: ChatUiState,
    modelLoadingState: ModelLoadingState,
    onGetReadyTapped: () -> Unit,
    onOpenModelLibrary: () -> Unit,
    canLoadLastUsedModel: Boolean,
    lastUsedModelLabel: String?,
    onLoadLastUsedModel: () -> Unit,
    activeRuntimeModelLabel: String?,
    onOpenRuntimeControls: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onRefreshRuntimeChecks: () -> Unit,
) {
    var showTechnicalDetails by remember(state.runtime.lastErrorTechnicalDetail) {
        mutableStateOf(false)
    }

    val lifecycleChipColors = when (modelLoadingState) {
        is ModelLoadingState.Loaded -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        is ModelLoadingState.Loading -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        is ModelLoadingState.Offloading -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        is ModelLoadingState.Error -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        is ModelLoadingState.Idle -> AssistChipDefaults.assistChipColors()
    }
    val lifecycleIcon = when (modelLoadingState) {
        is ModelLoadingState.Loaded -> Icons.Filled.CheckCircle
        is ModelLoadingState.Loading -> Icons.Filled.Sync
        is ModelLoadingState.Offloading -> Icons.Filled.HourglassEmpty
        is ModelLoadingState.Error -> Icons.Filled.Error
        is ModelLoadingState.Idle -> Icons.Filled.RadioButtonUnchecked
    }
    val lifecycleLabel = modelLoadingState.readableRuntimeStateLabel()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = onOpenRuntimeControls,
                    colors = lifecycleChipColors,
                    leadingIcon = {
                        Icon(
                            imageVector = lifecycleIcon,
                            contentDescription = null,
                        )
                    },
                    label = { StatusChipLabel(lifecycleLabel) },
                )
                if (!activeRuntimeModelLabel.isNullOrBlank()) {
                    AssistChip(
                        onClick = onOpenRuntimeControls,
                        label = { StatusChipLabel(activeRuntimeModelLabel) },
                    )
                }
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
                        style = MaterialTheme.typography.bodySmall,
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

            if (modelLoadingState is ModelLoadingState.Loading || modelLoadingState is ModelLoadingState.Offloading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenRuntimeControls) {
                        Text(stringResource(id = R.string.ui_open_runtime_controls))
                    }
                }
            } else if (state.runtime.modelRuntimeStatus != ModelRuntimeStatus.READY) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onGetReadyTapped) {
                        Text(stringResource(id = R.string.ui_get_ready))
                    }
                    TextButton(onClick = onOpenModelLibrary) {
                        Text(stringResource(id = R.string.ui_open_model_setup))
                    }
                    TextButton(onClick = onOpenRuntimeControls) {
                        Text(stringResource(id = R.string.ui_open_runtime_controls))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenAdvanced) {
                        Text(stringResource(id = R.string.ui_open_advanced_controls))
                    }
                    TextButton(
                        onClick = onRefreshRuntimeChecks,
                        enabled = state.runtime.startupProbeState != StartupProbeState.RUNNING,
                    ) {
                        Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                    }
                }
            }

            AnimatedVisibility(
                visible = canLoadLastUsedModel && !lastUsedModelLabel.isNullOrBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                if (!lastUsedModelLabel.isNullOrBlank()) {
                    TextButton(onClick = onLoadLastUsedModel) {
                        Text(stringResource(id = R.string.ui_model_runtime_load_last_used_short, lastUsedModelLabel))
                    }
                }
            }

            AnimatedVisibility(
                visible = state.runtime.lastErrorUserMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                state.runtime.lastErrorUserMessage?.let { errorMessage ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                                Text(
                                    if (showTechnicalDetails) {
                                        stringResource(id = R.string.ui_hide_technical_details)
                                    } else {
                                        stringResource(id = R.string.ui_show_technical_details)
                                    },
                                )
                            }
                            if (showTechnicalDetails) {
                                state.runtime.lastErrorTechnicalDetail?.let { technical ->
                                    Text(
                                        text = technical,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                            Text(
                                text = stringResource(id = state.runtime.recoveryHintTextResId()),
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelLoadingState.readableRuntimeStateLabel(): String {
    return when (this) {
        is ModelLoadingState.Idle -> stringResource(id = R.string.ui_model_runtime_state_unloaded)
        is ModelLoadingState.Loading -> stringResource(id = R.string.ui_model_runtime_state_loading)
        is ModelLoadingState.Loaded -> stringResource(id = R.string.ui_model_runtime_state_loaded)
        is ModelLoadingState.Offloading -> {
            if (queued) {
                stringResource(id = R.string.ui_model_runtime_state_offloading_queued)
            } else {
                stringResource(id = R.string.ui_model_runtime_state_offloading)
            }
        }

        is ModelLoadingState.Error -> stringResource(id = R.string.ui_model_runtime_state_failed)
    }
}

internal fun RuntimeUiState.recoveryHintTextResId(): Int {
    val nativeRuntimeMissing = lastErrorTechnicalDetail
        ?.contains("libpocket_llama.so", ignoreCase = true) == true ||
        lastErrorTechnicalDetail?.contains("build is missing native runtime library", ignoreCase = true) == true
    val timeoutError = lastErrorTechnicalDetail
        ?.contains("timed out", ignoreCase = true) == true ||
        lastErrorUserMessage?.contains("timed out", ignoreCase = true) == true
    return if (nativeRuntimeMissing) {
        R.string.ui_native_runtime_missing_hint
    } else if (timeoutError) {
        R.string.ui_runtime_timeout_hint
    } else {
        R.string.ui_model_setup_hint
    }
}

@Composable
internal fun StatusChipLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp),
    )
}
