package com.pocketagent.android.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            ),
        )
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
        Text(
            text = "Completion Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SliderSetting(
            label = "Temperature",
            value = temperature,
            valueRange = 0f..2f,
            valueLabel = "%.2f".format(temperature),
            onValueChange = { temperature = it },
            onValueChangeFinished = { emitUpdate() },
        )

        SliderSetting(
            label = "Top P",
            value = topP,
            valueRange = 0f..1f,
            valueLabel = "%.2f".format(topP),
            onValueChange = { topP = it },
            onValueChangeFinished = { emitUpdate() },
        )

        SliderSetting(
            label = "Top K",
            value = topK.toFloat(),
            valueRange = 1f..200f,
            valueLabel = topK.toString(),
            onValueChange = { topK = it.roundToInt() },
            onValueChangeFinished = { emitUpdate() },
        )

        SliderSetting(
            label = "Max Tokens",
            value = maxTokens.toFloat(),
            valueRange = 128f..8192f,
            valueLabel = maxTokens.toString(),
            onValueChange = { maxTokens = it.roundToInt() },
            onValueChangeFinished = { emitUpdate() },
        )

        SliderSetting(
            label = "Repeat Penalty",
            value = repeatPenalty,
            valueRange = 0.5f..2f,
            valueLabel = "%.2f".format(repeatPenalty),
            onValueChange = { repeatPenalty = it },
            onValueChangeFinished = { emitUpdate() },
        )

        SliderSetting(
            label = "Frequency Penalty",
            value = frequencyPenalty,
            valueRange = 0f..2f,
            valueLabel = "%.2f".format(frequencyPenalty),
            onValueChange = { frequencyPenalty = it },
            onValueChangeFinished = { emitUpdate() },
        )

        SliderSetting(
            label = "Presence Penalty",
            value = presencePenalty,
            valueRange = 0f..2f,
            valueLabel = "%.2f".format(presencePenalty),
            onValueChange = { presencePenalty = it },
            onValueChangeFinished = { emitUpdate() },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = "System Prompt",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
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
            placeholder = { Text("Enter a system prompt...") },
        )

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SliderSetting(
    label: String,
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
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth(),
    )
}
