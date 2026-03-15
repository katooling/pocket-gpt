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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.runtime.GpuProbeFailureReason
import com.pocketagent.android.runtime.GpuProbeStatus
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.RuntimeUiState
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
    wifiOnlyDownloadsEnabled: Boolean,
    onRoutingModeSelected: (RoutingMode) -> Unit,
    onPerformanceProfileSelected: (RuntimePerformanceProfile) -> Unit,
    onKeepAlivePreferenceSelected: (RuntimeKeepAlivePreference) -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onGpuAccelerationEnabledChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
    onOpenModelLibrary: () -> Unit,
    onOpenRuntimeControls: () -> Unit,
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

        // --- Quick actions (most used) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .testTag("open_model_library_button"),
                onClick = onOpenModelLibrary,
            ) {
                Text(stringResource(id = R.string.ui_open_model_library))
            }
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .testTag("open_runtime_controls_button"),
                onClick = onOpenRuntimeControls,
            ) {
                Text(stringResource(id = R.string.ui_open_runtime_controls))
            }
        }

        HorizontalDivider()

        // --- Speed & Battery ---
        Text(stringResource(id = R.string.ui_speed_battery_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(id = R.string.ui_speed_battery_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RuntimePerformanceProfile.entries.forEach { profile ->
            val (label, description) = performanceProfileLabels(profile)
            val profileDescription = stringResource(id = R.string.a11y_performance_profile, label)
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
                Column {
                    Text(label)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()

        // --- Downloads ---
        Text(stringResource(id = R.string.ui_download_controls_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(id = R.string.ui_download_controls_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = wifiOnlyDownloadsEnabled,
                    role = Role.Switch,
                    onValueChange = onWifiOnlyDownloadsChanged,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Switch(
                checked = wifiOnlyDownloadsEnabled,
                onCheckedChange = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(id = R.string.ui_download_wifi_only_toggle))
        }

        HorizontalDivider()

        // --- Keep-alive ---
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

        // --- GPU acceleration (Experimental) ---
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
        if (shouldShowOpenClQuantizationWarning(state)) {
            Text(
                text = stringResource(id = R.string.ui_gpu_acceleration_opencl_quant_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()

        // --- Model selection (advanced, rarely changed) ---
        Text(stringResource(id = R.string.ui_model_selection_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(id = R.string.ui_model_selection_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        supportedRoutingModes().forEach { mode ->
            val (label, description) = routingModeLabels(mode)
            val routingDescription = stringResource(id = R.string.a11y_routing_mode, label)
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
                Column {
                    Text(label)
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // --- Diagnostics (collapsible) ---
        DiagnosticsSection(
            runtime = state.runtime,
            onExportDiagnostics = onExportDiagnostics,
        )
    }
}

@Composable
private fun DiagnosticsSection(
    runtime: RuntimeUiState,
    onExportDiagnostics: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(id = R.string.ui_diagnostics_section_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = if (expanded) "Hide" else "Show",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (expanded) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DiagnosticLine(
                    label = "Runtime",
                    value = when (runtime.modelRuntimeStatus) {
                        ModelRuntimeStatus.NOT_READY -> stringResource(id = R.string.ui_model_status_not_ready)
                        ModelRuntimeStatus.LOADING -> stringResource(id = R.string.ui_model_status_loading)
                        ModelRuntimeStatus.READY -> stringResource(id = R.string.ui_model_status_ready)
                        ModelRuntimeStatus.ERROR -> stringResource(id = R.string.ui_model_status_error)
                    },
                )
                runtime.runtimeBackend?.let { DiagnosticLine("Backend", it) }
                runtime.modelStatusDetail?.let { DiagnosticLine("Detail", it) }
                runtime.activeModelId?.let { DiagnosticLine("Active model", it) }
                runtime.lastFirstTokenLatencyMs?.let { DiagnosticLine("First token", "${it}ms") }
                runtime.lastTotalLatencyMs?.let { DiagnosticLine("Total latency", "${it}ms") }
                runtime.lastPrefillMs?.let { DiagnosticLine("Prefill", "${it}ms") }
                runtime.lastDecodeMs?.let { DiagnosticLine("Decode", "${it}ms") }
                runtime.lastTokensPerSec?.let { DiagnosticLine("Decode rate", "${"%.2f".format(it)} tok/s") }
                runtime.lastPeakRssMb?.let { DiagnosticLine("Peak RSS", "${"%.0f".format(it)} MB") }
            }
        }
        TextButton(onClick = onExportDiagnostics) {
            Text(stringResource(id = R.string.ui_export_diagnostics))
        }
        Text(
            text = "Diagnostics export includes runtime tuning recommendations and recent benchmark samples.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun performanceProfileLabels(profile: RuntimePerformanceProfile): Pair<String, String> {
    return when (profile) {
        RuntimePerformanceProfile.BATTERY -> Pair(
            stringResource(id = R.string.ui_performance_profile_battery),
            stringResource(id = R.string.ui_performance_profile_battery_desc),
        )
        RuntimePerformanceProfile.BALANCED -> Pair(
            stringResource(id = R.string.ui_performance_profile_balanced),
            stringResource(id = R.string.ui_performance_profile_balanced_desc),
        )
        RuntimePerformanceProfile.FAST -> Pair(
            stringResource(id = R.string.ui_performance_profile_fast),
            stringResource(id = R.string.ui_performance_profile_fast_desc),
        )
    }
}

@Composable
private fun routingModeLabels(mode: RoutingMode): Pair<String, String> {
    return when (mode) {
        RoutingMode.AUTO -> Pair(
            stringResource(id = R.string.ui_routing_mode_auto),
            stringResource(id = R.string.ui_routing_mode_auto_desc),
        )
        else -> Pair(mode.name, "")
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

private fun shouldShowOpenClQuantizationWarning(state: ChatUiState): Boolean {
    return shouldShowOpenClQuantizationWarning(runtime = state.runtime)
}

internal fun shouldShowOpenClQuantizationWarning(runtime: RuntimeUiState): Boolean {
    if (!runtime.gpuAccelerationEnabled) {
        return false
    }
    val activeBackend = runtime.activeBackend?.trim()?.lowercase().orEmpty()
    val backendProfile = runtime.backendProfile?.trim()?.lowercase().orEmpty()
    val compiledBackends = runtime.compiledBackend?.trim()?.lowercase().orEmpty()
    val backendMayUseOpenCl = activeBackend == "opencl" ||
        backendProfile == "opencl" ||
        (backendProfile == "auto" && compiledBackends.contains("opencl"))
    if (!backendMayUseOpenCl) {
        return false
    }
    val quantHint = runtime.activeModelQuantization?.trim()?.lowercase().orEmpty()
    val modelId = runtime.activeModelId?.trim()?.lowercase().orEmpty()
    val quantSource = when {
        quantHint.isNotBlank() && quantHint != "unknown" -> quantHint
        modelId.isNotBlank() -> modelId
        else -> ""
    }
    if (quantSource.isBlank()) {
        return false
    }
    if (OPENCL_SAFE_QUANT_MODEL_REGEX.containsMatchIn(quantSource)) {
        return false
    }
    return OPENCL_UNSUPPORTED_QUANT_MODEL_REGEX.containsMatchIn(quantSource)
}

private val OPENCL_SAFE_QUANT_MODEL_REGEX = Regex(
    """(?:^|[._-])(q4[._-]?0|q6[._-]?k|q8[._-]?0|f16|f32|fp16|fp32|mxfp4(?:[._-]moe)?)(?:[._-]|$)""",
    RegexOption.IGNORE_CASE,
)

private val OPENCL_UNSUPPORTED_QUANT_MODEL_REGEX = Regex(
    """(?:^|[._-])(q(?:[2-8][._-](?:k|[0-9])[._-]?[a-z0-9_]*|5|8)|iq[1-4](?:[._-][a-z]+)?)(?:[._-]|$)""",
    RegexOption.IGNORE_CASE,
)
