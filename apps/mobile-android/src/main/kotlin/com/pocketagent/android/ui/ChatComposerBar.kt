package com.pocketagent.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ChatGateState
import com.pocketagent.android.ui.state.ChatGateStatus

@Composable
internal fun ComposerBar(
    text: String,
    isSending: Boolean,
    isCancelling: Boolean = false,
    chatGateState: ChatGateState,
    editingMessageId: String? = null,
    attachedImages: List<String> = emptyList(),
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onSubmitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onAttachImage: () -> Unit,
    onRemoveImage: (Int) -> Unit = {},
    showThinkingToggle: Boolean = false,
    thinkingEnabled: Boolean = false,
    onToggleThinking: () -> Unit = {},
    onOpenCompletionSettings: () -> Unit = {},
    onBlockedAction: (ChatGatePrimaryAction) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val isEditing = editingMessageId != null
    val canTriggerBlockedAction = hasChatGatePrimaryAction(chatGateState)
    val canTriggerUserAction = chatGateState.isReady || canTriggerBlockedAction
    val sendButtonDescription = stringResource(id = R.string.a11y_send_button)
    val sendStateDescription = when {
        isSending && isCancelling -> stringResource(id = R.string.a11y_send_state_cancelling)
        isSending -> stringResource(id = R.string.a11y_send_state_sending)
        !chatGateState.isReady -> stringResource(id = R.string.a11y_send_state_runtime_not_ready)
        text.isBlank() -> stringResource(id = R.string.a11y_send_state_disabled)
        else -> stringResource(id = R.string.a11y_send_state_enabled)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (shouldShowChatGateInlineCard(chatGateState)) {
            ChatGateInlineCard(chatGateState = chatGateState, onPrimaryAction = onBlockedAction)
            Spacer(modifier = Modifier.size(8.dp))
        }
        if (isEditing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Editing message",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onCancelEdit, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel edit",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (attachedImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                attachedImages.forEachIndexed { index, path ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(
                                text = path.substringAfterLast('/').take(20),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            IconButton(
                                onClick = { onRemoveImage(index) },
                                modifier = Modifier.size(18.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove image",
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    if (chatGateState.isReady) onAttachImage() else onBlockedAction(chatGateState.primaryAction)
                },
                enabled = !isSending && canTriggerUserAction,
            ) {
                Icon(Icons.Default.Image, contentDescription = stringResource(id = R.string.a11y_attach_image))
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.weight(1f).testTag("composer_input"),
                label = { Text(stringResource(id = R.string.ui_composer_label)) },
                enabled = !isSending,
                maxLines = 4,
            )
            if (showThinkingToggle) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleThinking()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = if (thinkingEnabled) "Disable thinking" else "Enable thinking",
                        tint = if (thinkingEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onOpenCompletionSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Chat settings",
                    modifier = Modifier.size(20.dp),
                )
            }
            Button(
                modifier = Modifier
                    .testTag("send_button")
                    .semantics {
                        contentDescription = sendButtonDescription
                        stateDescription = sendStateDescription
                    },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    when {
                        isSending && !isCancelling -> onCancelSend()
                        isEditing -> onSubmitEdit()
                        chatGateState.isReady -> onSend()
                        else -> onBlockedAction(chatGateState.primaryAction)
                    }
                },
                enabled = if (isSending) !isCancelling else text.isNotBlank() && canTriggerUserAction,
            ) {
                Text(
                    when {
                        isSending && isCancelling -> stringResource(id = R.string.ui_cancelling_button)
                        isSending -> stringResource(id = R.string.ui_cancel_button)
                        isEditing -> "Update"
                        else -> stringResource(id = R.string.ui_send_button)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ChatGateInlineCard(
    chatGateState: ChatGateState,
    onPrimaryAction: (ChatGatePrimaryAction) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("chat_gate_inline_card")) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = chatGateInlineMessage(chatGateState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            chatGatePrimaryActionLabelResId(chatGateState.primaryAction)?.let { labelId ->
                OutlinedButton(onClick = { onPrimaryAction(chatGateState.primaryAction) }) {
                    Text(stringResource(id = labelId))
                }
            }
        }
    }
}

@Composable
internal fun chatGateInlineMessage(chatGateState: ChatGateState): String {
    return when (chatGateState.status) {
        ChatGateStatus.READY -> ""
        ChatGateStatus.BLOCKED_MODEL_MISSING -> stringResource(id = R.string.ui_chat_gate_blocked_model_missing)
        ChatGateStatus.BLOCKED_RUNTIME_CHECK -> stringResource(id = R.string.ui_chat_gate_blocked_runtime_check)
        ChatGateStatus.LOADING_MODEL -> chatGateState.detail?.takeIf { it.isNotBlank() }
            ?: stringResource(id = R.string.ui_chat_gate_loading)
        ChatGateStatus.ERROR_RECOVERABLE -> chatGateState.detail?.takeIf { it.isNotBlank() }
            ?: stringResource(id = R.string.ui_chat_gate_recoverable)
    }
}

internal fun shouldShowChatGateInlineCard(chatGateState: ChatGateState): Boolean {
    return chatGateState.status != ChatGateStatus.READY
}

internal fun hasChatGatePrimaryAction(chatGateState: ChatGateState): Boolean {
    return chatGateState.primaryAction != ChatGatePrimaryAction.NONE
}

internal fun chatGatePrimaryActionLabelResId(action: ChatGatePrimaryAction): Int? {
    return when (action) {
        ChatGatePrimaryAction.NONE -> null
        ChatGatePrimaryAction.GET_READY -> R.string.ui_get_ready
        ChatGatePrimaryAction.OPEN_MODEL_SETUP -> R.string.ui_open_model_setup
        ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS -> R.string.ui_refresh_runtime_checks
    }
}
