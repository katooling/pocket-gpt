package com.pocketagent.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R

@Composable
internal fun ToolDialog(
    onDismiss: () -> Unit,
    onUsePrompt: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.ui_local_tools_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(id = R.string.ui_tool_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToolSuggestionCard(
                    prompt = stringResource(id = R.string.ui_tool_calculator_prompt),
                    onUsePrompt = onUsePrompt,
                    onDismiss = onDismiss,
                )
                ToolSuggestionCard(
                    prompt = stringResource(id = R.string.ui_tool_date_time_prompt),
                    onUsePrompt = onUsePrompt,
                    onDismiss = onDismiss,
                )
                ToolSuggestionCard(
                    prompt = stringResource(id = R.string.ui_tool_local_search_prompt),
                    onUsePrompt = onUsePrompt,
                    onDismiss = onDismiss,
                )
                ToolSuggestionCard(
                    prompt = stringResource(id = R.string.ui_tool_notes_prompt),
                    onUsePrompt = onUsePrompt,
                    onDismiss = onDismiss,
                )
                ToolSuggestionCard(
                    prompt = stringResource(id = R.string.ui_tool_reminder_prompt),
                    onUsePrompt = onUsePrompt,
                    onDismiss = onDismiss,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.ui_close)) }
        },
    )
}

@Composable
private fun ToolSuggestionCard(
    prompt: String,
    onUsePrompt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        onClick = {
            onUsePrompt(prompt)
            onDismiss()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = prompt,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
