package com.pocketagent.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeKeepAlivePreference
import com.pocketagent.core.RoutingMode
import com.pocketagent.runtime.RuntimePerformanceProfile

@Composable
internal fun PrivacyInfoSheet(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(id = R.string.ui_privacy_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(id = R.string.ui_privacy_item_1), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(id = R.string.ui_privacy_item_2), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(id = R.string.ui_privacy_item_3), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.ui_close))
        }
    }
}

@Composable
internal fun AdvancedSettingsSheet(
    state: ChatUiState,
    onRoutingModeSelected: (RoutingMode) -> Unit,
    onPerformanceProfileSelected: (RuntimePerformanceProfile) -> Unit,
    onKeepAlivePreferenceSelected: (RuntimeKeepAlivePreference) -> Unit,
    onGpuAccelerationEnabledChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
    onOpenModelSetup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(id = R.string.ui_advanced_controls_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(id = R.string.ui_speed_battery_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(id = R.string.ui_speed_battery_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RuntimePerformanceProfile.entries.forEach { profile ->
            val profileDescription = stringResource(id = R.string.a11y_performance_profile, profile.name)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.runtime.performanceProfile == profile,
                        onClick = { onPerformanceProfileSelected(profile) },
                        role = Role.RadioButton,
                    )
                    .semantics { contentDescription = profileDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.runtime.performanceProfile == profile,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(profile.name)
            }
        }

        HorizontalDivider()
        Text(stringResource(id = R.string.ui_keep_alive_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(id = R.string.ui_keep_alive_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RuntimeKeepAlivePreference.entries.forEach { preference ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.runtime.keepAlivePreference == preference,
                        onClick = { onKeepAlivePreferenceSelected(preference) },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.runtime.keepAlivePreference == preference,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(keepAlivePreferenceLabel(preference))
            }
        }

        HorizontalDivider()
        Text(stringResource(id = R.string.ui_advanced_experimental_title), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.runtime.gpuAccelerationEnabled,
                    enabled = state.runtime.gpuAccelerationSupported,
                    role = Role.Switch,
                    onValueChange = { checked -> onGpuAccelerationEnabledChanged(checked) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Switch(
                checked = state.runtime.gpuAccelerationEnabled,
                enabled = state.runtime.gpuAccelerationSupported,
                onCheckedChange = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    state.runtime.gpuProbeStatus == GpuProbeStatus.PENDING ->
                        stringResource(id = R.string.ui_gpu_acceleration_validating)
                    state.runtime.gpuAccelerationSupported ->
                        stringResource(id = R.string.ui_gpu_acceleration_toggle)
                    else ->
                        stringResource(
                            id = R.string.ui_gpu_acceleration_unavailable_with_reason,
                            gpuProbeFailureReasonLabel(state.runtime.gpuProbeFailureReason),
                        )
                },
            )
        }
        Text(
            text = stringResource(id = R.string.ui_gpu_acceleration_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text(stringResource(id = R.string.ui_model_selection_title), style = MaterialTheme.typography.labelLarge)

        supportedRoutingModes().forEach { mode ->
            val routingDescription = stringResource(id = R.string.a11y_routing_mode, mode.name)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.runtime.routingMode == mode,
                        onClick = { onRoutingModeSelected(mode) },
                        role = Role.RadioButton,
                    )
                    .semantics { contentDescription = routingDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.runtime.routingMode == mode,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(mode.name)
            }
        }

        HorizontalDivider()

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onExportDiagnostics,
        ) {
            Text(stringResource(id = R.string.ui_export_diagnostics))
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenModelSetup,
        ) {
            Text(stringResource(id = R.string.ui_open_model_setup))
        }

        Text(
            text = stringResource(
                id = R.string.ui_model_status_label,
                when (state.runtime.modelRuntimeStatus) {
                    ModelRuntimeStatus.NOT_READY -> stringResource(id = R.string.ui_model_status_not_ready)
                    ModelRuntimeStatus.LOADING -> stringResource(id = R.string.ui_model_status_loading)
                    ModelRuntimeStatus.READY -> stringResource(id = R.string.ui_model_status_ready)
                    ModelRuntimeStatus.ERROR -> stringResource(id = R.string.ui_model_status_error)
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        state.runtime.runtimeBackend?.let { backend ->
            Text(
                text = stringResource(id = R.string.ui_runtime_backend_label, backend),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.modelStatusDetail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.runtime.activeModelId?.let { modelId ->
            Text(
                text = stringResource(id = R.string.ui_active_model_label, modelId),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.lastFirstTokenLatencyMs?.let { latency ->
            Text(
                text = stringResource(id = R.string.ui_first_token_latency_label, latency),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.lastTotalLatencyMs?.let { latency ->
            Text(
                text = stringResource(id = R.string.ui_total_latency_label, latency),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.lastPrefillMs?.let { latency ->
            Text(
                text = "Last prefill latency: ${latency}ms",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.lastDecodeMs?.let { latency ->
            Text(
                text = "Last decode latency: ${latency}ms",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.lastTokensPerSec?.let { value ->
            Text(
                text = "Last decode rate: ${"%.2f".format(value)} tok/s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.lastPeakRssMb?.let { value ->
            Text(
                text = "Last peak RSS: ${"%.0f".format(value)} MB",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = "Diagnostics export includes runtime tuning recommendations and recent benchmark samples.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun gpuProbeFailureReasonLabel(reason: String?): String {
    return when (reason) {
        GpuProbeFailureReason.MODEL_UNAVAILABLE.name ->
            stringResource(id = R.string.ui_gpu_acceleration_reason_model_required)
        null, "" ->
            stringResource(id = R.string.ui_gpu_acceleration_reason_unknown)
        else -> reason.lowercase().replace('_', ' ')
    }
}

@Composable
private fun keepAlivePreferenceLabel(preference: RuntimeKeepAlivePreference): String {
    return when (preference) {
        RuntimeKeepAlivePreference.AUTO -> stringResource(id = R.string.ui_keep_alive_auto)
        RuntimeKeepAlivePreference.ALWAYS -> stringResource(id = R.string.ui_keep_alive_always)
        RuntimeKeepAlivePreference.ONE_MINUTE -> stringResource(id = R.string.ui_keep_alive_one_minute)
        RuntimeKeepAlivePreference.FIVE_MINUTES -> stringResource(id = R.string.ui_keep_alive_five_minutes)
        RuntimeKeepAlivePreference.FIFTEEN_MINUTES -> stringResource(id = R.string.ui_keep_alive_fifteen_minutes)
        RuntimeKeepAlivePreference.UNLOAD_IMMEDIATELY -> stringResource(id = R.string.ui_keep_alive_unload_immediately)
    }
}
