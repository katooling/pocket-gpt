package com.pocketagent.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.tickConfirm
import com.pocketagent.android.ui.theme.tickLight

@Composable
internal fun MessageBubble(
    message: MessageUiModel,
    runtimeStatusDetail: String?,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    clipboardManager: ClipboardManager,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val isUser = message.role == MessageRole.USER
    var showContextMenu by remember { mutableStateOf(false) }
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val bubbleShape = messageBubbleShape(
        isUser = isUser,
        isFirstInGroup = isFirstInGroup,
        isLastInGroup = isLastInGroup,
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            tonalElevation = 1.dp,
            color = messageBubbleColor(message.role),
            modifier = Modifier
                .clip(bubbleShape)
                .fillMaxWidth(0.9f)
                .widthIn(max = 600.dp),
        ) {
            Box {
                val messageOptionsLabel = stringResource(R.string.cd_message_options)
                Column(
                    modifier = Modifier
                        .semantics {
                            onLongClick(label = messageOptionsLabel) { true }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    if (message.content.isNotBlank()) {
                                        haptic.tickConfirm()
                                        showContextMenu = true
                                    }
                                },
                            )
                        }
                        .padding(PocketAgentDimensions.messageBubblePadding),
                ) {
                    val attachmentPaths = message.imagePaths.ifEmpty { listOfNotNull(message.imagePath) }
                    when {
                        attachmentPaths.isNotEmpty() -> {
                            Text(
                                text = stringResource(id = R.string.ui_images_attached, attachmentPaths.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(compactSpacing))
                            AttachmentThumbnailRow(attachmentPaths)
                            Spacer(modifier = Modifier.height(compactSpacing))
                        }

                        message.toolName != null -> {
                            val statusSuffix = message.interaction?.toolCalls?.firstOrNull()?.status.toReadableSuffix()
                            Text(
                                text = stringResource(id = R.string.ui_tool_message_label, message.toolName) + statusSuffix,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(compactSpacing))
                        }
                    }

                    if (!message.reasoningContent.isNullOrBlank()) {
                        ThinkingBubble(reasoningContent = message.reasoningContent)
                        Spacer(modifier = Modifier.height(compactSpacing + (compactSpacing / 2)))
                    }

                    val content = message.content
                    if (shouldRenderInThreadLoadingPlaceholder(message)) {
                        if (message.isThinking) {
                            ThinkingInProgressIndicator()
                        } else {
                            LoadingDotsAnimation()
                        }
                        runtimeStatusDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Spacer(modifier = Modifier.height(compactSpacing))
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (message.role == MessageRole.ASSISTANT) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            MarkdownMessageContent(content = content, clipboardManager = clipboardManager)
                        }
                    } else {
                        MarkdownMessageContent(content = content, clipboardManager = clipboardManager)
                    }

                    MessageMetrics(message)
                    FailedMessageRetry(message, onRegenerateMessage)
                    MessageActions(
                        message = message,
                        onEditMessage = onEditMessage,
                        onRegenerateMessage = onRegenerateMessage,
                        onCopiedToClipboard = onCopiedToClipboard,
                        clipboardManager = clipboardManager,
                    )
                }

                MessageContextMenu(
                    message = message,
                    expanded = showContextMenu,
                    onDismiss = { showContextMenu = false },
                    onEditMessage = onEditMessage,
                    onRegenerateMessage = onRegenerateMessage,
                    onCopiedToClipboard = onCopiedToClipboard,
                    clipboardManager = clipboardManager,
                )
            }
        }
    }
}

@Composable
private fun MessageMetrics(message: MessageUiModel) {
    if (message.role != MessageRole.ASSISTANT || message.isStreaming || message.content.isBlank()) return
    val hasMetadata = message.tokensPerSec != null || message.firstTokenMs != null || message.totalLatencyMs != null
    if (!hasMetadata) return
    var expanded by remember(message.id) { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing / 2))
    if (expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = false },
            horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
        ) {
            val style = MaterialTheme.typography.labelSmall
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            message.tokensPerSec?.let { Text(stringResource(id = R.string.ui_metric_tokens_per_sec, it), style = style, color = color) }
            message.firstTokenMs?.let { Text(stringResource(id = R.string.ui_metric_ttft, it), style = style, color = color) }
            message.totalLatencyMs?.let { Text(stringResource(id = R.string.ui_metric_total_latency, it), style = style, color = color) }
        }
    } else {
        message.tokensPerSec?.let { tps ->
            Text(
                text = stringResource(id = R.string.ui_metric_tokens_per_sec, tps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.clickable { expanded = true },
            )
        }
    }
}

@Composable
private fun FailedMessageRetry(
    message: MessageUiModel,
    onRegenerateMessage: (String) -> Unit,
) {
    val isFailed = message.role == MessageRole.ASSISTANT &&
        !message.isStreaming &&
        message.finishReason?.let {
            it.startsWith("failed") || it == "timeout" || it == "cancelled"
        } == true
    if (!isFailed) return
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    Spacer(modifier = Modifier.height(compactSpacing))
    Surface(
        onClick = { onRegenerateMessage(message.id) },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = PocketAgentDimensions.messageBubblePadding,
                vertical = PocketAgentDimensions.sectionSpacing,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(compactSpacing + (compactSpacing / 2)),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(id = R.string.a11y_retry),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(id = R.string.ui_retry),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun MessageActions(
    message: MessageUiModel,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    clipboardManager: ClipboardManager,
) {
    if (message.content.isBlank() || message.isStreaming) return
    val haptic = LocalHapticFeedback.current
    // Only show Copy inline — Edit and Regenerate are available via long-press context menu
    Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing / 2))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                haptic.tickLight()
                clipboardManager.setText(AnnotatedString(message.content))
                onCopiedToClipboard()
            },
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(id = R.string.a11y_copy_message),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun MessageContextMenu(
    message: MessageUiModel,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    clipboardManager: ClipboardManager,
) {
    val haptic = LocalHapticFeedback.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(id = R.string.ui_copy)) },
            onClick = {
                haptic.tickLight()
                clipboardManager.setText(AnnotatedString(message.content))
                onCopiedToClipboard()
                onDismiss()
            },
        )
        if (message.role == MessageRole.USER) {
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.ui_edit)) },
                onClick = {
                    onEditMessage(message.id)
                    onDismiss()
                },
            )
        }
        if (message.role == MessageRole.ASSISTANT) {
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.ui_regenerate)) },
                onClick = {
                    onRegenerateMessage(message.id)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun messageBubbleColor(role: MessageRole) = when (role) {
    MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
    MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
    MessageRole.TOOL -> MaterialTheme.colorScheme.secondaryContainer
    MessageRole.SYSTEM -> MaterialTheme.colorScheme.errorContainer
}

private fun messageBubbleShape(
    isUser: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
): RoundedCornerShape {
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val cornerRadius = PocketAgentDimensions.messageBubblePadding
    val smallCorner = compactSpacing
    return when {
        isFirstInGroup && isLastInGroup -> RoundedCornerShape(cornerRadius)
        isUser && isFirstInGroup -> RoundedCornerShape(cornerRadius, cornerRadius, smallCorner, cornerRadius)
        isUser && isLastInGroup -> RoundedCornerShape(cornerRadius, smallCorner, cornerRadius, cornerRadius)
        isUser -> RoundedCornerShape(cornerRadius, smallCorner, smallCorner, cornerRadius)
        !isUser && isFirstInGroup -> RoundedCornerShape(cornerRadius, cornerRadius, cornerRadius, smallCorner)
        !isUser && isLastInGroup -> RoundedCornerShape(smallCorner, cornerRadius, cornerRadius, cornerRadius)
        else -> RoundedCornerShape(smallCorner, cornerRadius, cornerRadius, smallCorner)
    }
}
