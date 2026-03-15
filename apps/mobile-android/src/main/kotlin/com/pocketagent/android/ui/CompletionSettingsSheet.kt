package com.pocketagent.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.CompletionSettings
import kotlin.math.roundToInt

@Composable
internal fun CompletionSettingsSheet(
    settings: CompletionSettings,
    onSettingsChanged: (CompletionSettings) -> Unit,
    onClose: () -> Unit,
) {
    var temperature by remember(settings) { mutableFloatStateOf(settings.temperature) }
    var topP by remember(settings) { mutableFloatStateOf(settings.topP) }
    var topK by remember(settings) { mutableIntStateOf(settings.topK) }
    var maxTokens by remember(settings) { mutableIntStateOf(settings.maxTokens) }
    var repeatPenalty by remember(settings) { mutableFloatStateOf(settings.repeatPenalty) }
    var frequencyPenalty by remember(settings) { mutableFloatStateOf(settings.frequencyPenalty) }
    var presencePenalty by remember(settings) { mutableFloatStateOf(settings.presencePenalty) }
    var systemPrompt by remember(settings) { mutableStateOf(settings.systemPrompt) }
    var showThinking by remember(settings) { mutableStateOf(settings.showThinking) }
    var showAdvanced by remember { mutableStateOf(false) }

    fun emitUpdate() {
        onSettingsChanged(
            CompletionSettings(
                temperature = temperature,
                topP = topP,
                topK = topK,
                maxTokens = maxTokens,
                repeatPenalty = repeatPenalty,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                systemPrompt = systemPrompt,
                showThinking = showThinking,
            ),
        )
    }

    fun resetDefaults() {
        val defaults = CompletionSettings()
        temperature = defaults.temperature
        topP = defaults.topP
        topK = defaults.topK
        maxTokens = defaults.maxTokens
        repeatPenalty = defaults.repeatPenalty
        frequencyPenalty = defaults.frequencyPenalty
        presencePenalty = defaults.presencePenalty
        systemPrompt = defaults.systemPrompt
        showThinking = defaults.showThinking
        onSettingsChanged(defaults)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.ui_completion_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { resetDefaults() }) {
                Text(stringResource(id = R.string.ui_completion_reset_defaults))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        // --- Common settings ---
        Text(
            text = stringResource(id = R.string.ui_completion_common_section),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))

        SliderSetting(
            label = stringResource(id = R.string.ui_completion_temperature_label),
            description = stringResource(id = R.string.ui_completion_temperature_desc),
            value = temperature,
            valueRange = 0f..2f,
            valueLabel = "%.2f".format(temperature),
            onValueChange = { temperature = it },
            onValueChangeFinished = { emitUpdate() },
        )

        SliderSetting(
            label = stringResource(id = R.string.ui_completion_max_tokens_label),
            description = stringResource(id = R.string.ui_completion_max_tokens_desc),
            value = maxTokens.toFloat(),
            valueRange = 128f..8192f,
            valueLabel = maxTokens.toString(),
            onValueChange = { maxTokens = it.roundToInt() },
            onValueChangeFinished = { emitUpdate() },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.ui_completion_show_thinking_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(id = R.string.ui_completion_show_thinking_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = showThinking,
                onCheckedChange = { checked ->
                    showThinking = checked
                    emitUpdate()
                },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(id = R.string.ui_completion_system_prompt_label),
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = {
                systemPrompt = it
                emitUpdate()
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            placeholder = { Text(stringResource(id = R.string.ui_completion_system_prompt_placeholder)) },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Advanced settings (collapsed by default) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAdvanced = !showAdvanced },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.ui_completion_advanced_section),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (showAdvanced) "Hide" else "Show",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (showAdvanced) {
            Spacer(modifier = Modifier.height(4.dp))

            SliderSetting(
                label = stringResource(id = R.string.ui_completion_top_p_label),
                description = stringResource(id = R.string.ui_completion_top_p_desc),
                value = topP,
                valueRange = 0f..1f,
                valueLabel = "%.2f".format(topP),
                onValueChange = { topP = it },
                onValueChangeFinished = { emitUpdate() },
            )

            SliderSetting(
                label = stringResource(id = R.string.ui_completion_top_k_label),
                description = stringResource(id = R.string.ui_completion_top_k_desc),
                value = topK.toFloat(),
                valueRange = 1f..200f,
                valueLabel = topK.toString(),
                onValueChange = { topK = it.roundToInt() },
                onValueChangeFinished = { emitUpdate() },
            )

            SliderSetting(
                label = stringResource(id = R.string.ui_completion_repeat_penalty_label),
                description = stringResource(id = R.string.ui_completion_repeat_penalty_desc),
                value = repeatPenalty,
                valueRange = 0.5f..2f,
                valueLabel = "%.2f".format(repeatPenalty),
                onValueChange = { repeatPenalty = it },
                onValueChangeFinished = { emitUpdate() },
            )

            SliderSetting(
                label = stringResource(id = R.string.ui_completion_frequency_penalty_label),
                description = stringResource(id = R.string.ui_completion_frequency_penalty_desc),
                value = frequencyPenalty,
                valueRange = 0f..2f,
                valueLabel = "%.2f".format(frequencyPenalty),
                onValueChange = { frequencyPenalty = it },
                onValueChangeFinished = { emitUpdate() },
            )

            SliderSetting(
                label = stringResource(id = R.string.ui_completion_presence_penalty_label),
                description = stringResource(id = R.string.ui_completion_presence_penalty_desc),
                value = presencePenalty,
                valueRange = 0f..2f,
                valueLabel = "%.2f".format(presencePenalty),
                onValueChange = { presencePenalty = it },
                onValueChangeFinished = { emitUpdate() },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(id = R.string.ui_completion_done))
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SliderSetting(
    label: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth(),
    )
}
