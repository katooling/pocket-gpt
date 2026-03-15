package com.pocketagent.android.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun OfflineAndStatusHeader(
    state: ChatUiState,
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
    val modelStatusText = when (state.runtime.modelRuntimeStatus) {
        ModelRuntimeStatus.NOT_READY -> stringResource(id = R.string.ui_model_status_not_ready)
        ModelRuntimeStatus.LOADING -> stringResource(id = R.string.ui_model_status_loading)
        ModelRuntimeStatus.READY -> stringResource(id = R.string.ui_model_status_ready)
        ModelRuntimeStatus.ERROR -> stringResource(id = R.string.ui_model_status_error)
    }

    @Composable
    fun StatusChips() {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val statusColors = when (state.runtime.modelRuntimeStatus) {
                ModelRuntimeStatus.READY -> AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                ModelRuntimeStatus.LOADING -> AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                ModelRuntimeStatus.ERROR -> AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                ModelRuntimeStatus.NOT_READY -> AssistChipDefaults.assistChipColors()
            }
            AssistChip(
                onClick = if (state.runtime.modelRuntimeStatus == ModelRuntimeStatus.READY) {
                    onOpenAdvanced
                } else {
                    onOpenRuntimeControls
                },
                label = { StatusChipLabel(modelStatusText) },
                colors = statusColors,
            )
            if (!activeRuntimeModelLabel.isNullOrBlank()) {
                AssistChip(
                    onClick = onOpenRuntimeControls,
                    label = { StatusChipLabel(activeRuntimeModelLabel) },
                )
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusChips()
            state.runtime.modelStatusDetail
                ?.takeIf { it.isNotBlank() }
                ?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            if (state.runtime.modelRuntimeStatus != ModelRuntimeStatus.READY) {
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
                    TextButton(onClick = onRefreshRuntimeChecks) {
                        Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                    }
                }
            }
            if (canLoadLastUsedModel && !lastUsedModelLabel.isNullOrBlank()) {
                TextButton(onClick = onLoadLastUsedModel) {
                    Text(stringResource(id = R.string.ui_model_runtime_load_last_used_short, lastUsedModelLabel))
                }
            }
            if (state.runtime.lastErrorUserMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = state.runtime.lastErrorUserMessage,
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
