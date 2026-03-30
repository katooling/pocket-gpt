package com.pocketagent.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.theme.PocketAgentDimensions

@Composable
internal fun ThinkingBubble(reasoningContent: String) {
    var expanded by remember { mutableStateOf(false) }
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(PocketAgentDimensions.sectionSpacing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        id = if (expanded) R.string.a11y_collapse_thinking else R.string.a11y_expand_thinking,
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(compactSpacing))
                Text(
                    text = stringResource(id = R.string.ui_thinking),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(compactSpacing))
                Text(
                    text = reasoningContent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = reasoningContent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun LoadingDotsAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading_dots")
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PocketAgentDimensions.animSlow, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot1",
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                PocketAgentDimensions.animSlow,
                delayMillis = PocketAgentDimensions.animFast,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot2",
    )
    val alpha3 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PocketAgentDimensions.animSlow, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot3",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(compactSpacing),
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text("●", color = dotColor.copy(alpha = alpha1), style = MaterialTheme.typography.bodyLarge)
        Text("●", color = dotColor.copy(alpha = alpha2), style = MaterialTheme.typography.bodyLarge)
        Text("●", color = dotColor.copy(alpha = alpha3), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun ThinkingInProgressIndicator() {
    Column(verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing / 2)) {
        LoadingDotsAnimation()
        Text(
            text = stringResource(id = R.string.ui_thinking_in_progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PersistedToolCallStatus?.toReadableSuffix(): String {
    return when (this) {
        PersistedToolCallStatus.PENDING -> stringResource(id = R.string.ui_tool_call_status_pending_suffix)
        PersistedToolCallStatus.RUNNING -> stringResource(id = R.string.ui_tool_call_status_running_suffix)
        PersistedToolCallStatus.COMPLETED -> stringResource(id = R.string.ui_tool_call_status_completed_suffix)
        PersistedToolCallStatus.FAILED -> stringResource(id = R.string.ui_tool_call_status_failed_suffix)
        null -> ""
    }
}
