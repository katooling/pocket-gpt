package com.pocketagent.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
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
    activeSessionId: String? = null,
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
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(activeSessionId) {
        if (activeSessionId != null) {
            focusRequester.requestFocus()
        }
    }
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
            .animateContentSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (shouldShowChatGateInlineCard(chatGateState)) {
            ChatGateInlineCard(chatGateState = chatGateState)
            Spacer(modifier = Modifier.size(8.dp))
        }
        if (isEditing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(id = R.string.ui_editing_message),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onCancelEdit) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(id = R.string.a11y_cancel_edit),
                        modifier = Modifier.size(18.dp),
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
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(id = R.string.a11y_remove_image),
                                    modifier = Modifier.size(14.dp),
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
                onClick = onAttachImage,
                enabled = !isSending,
            ) {
                Icon(Icons.Default.Image, contentDescription = stringResource(id = R.string.a11y_attach_image))
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.weight(1f).testTag("composer_input").focusRequester(focusRequester),
                label = { Text(stringResource(id = R.string.ui_composer_label)) },
                enabled = !isSending,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank() && chatGateState.isReady && !isSending) {
                            onSend()
                        }
                    },
                ),
            )
            if (showThinkingToggle) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleThinking()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = stringResource(id = if (thinkingEnabled) R.string.a11y_disable_thinking else R.string.a11y_enable_thinking),
                        tint = if (thinkingEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onOpenCompletionSettings) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = stringResource(id = R.string.a11y_chat_settings),
                    modifier = Modifier.size(20.dp),
                )
            }
            val sendInteractionSource = remember { MutableInteractionSource() }
            val isPressed by sendInteractionSource.collectIsPressedAsState()
            val sendScale by animateFloatAsState(
                targetValue = if (isPressed) 0.92f else 1f,
                label = "send_scale",
            )
            Button(
                modifier = Modifier
                    .testTag("send_button")
                    .graphicsLayer {
                        scaleX = sendScale
                        scaleY = sendScale
                    }
                    .semantics {
                        contentDescription = sendButtonDescription
                        stateDescription = sendStateDescription
                    },
                interactionSource = sendInteractionSource,
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
                        isEditing -> stringResource(id = R.string.ui_update_button)
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
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("chat_gate_inline_card")) {
        Text(
            text = chatGateInlineMessage(chatGateState),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
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
        ChatGatePrimaryAction.GET_READY -> R.string.ui_get_ready
        ChatGatePrimaryAction.OPEN_MODEL_SETUP -> R.string.ui_open_model_setup
        ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS -> R.string.ui_refresh_runtime_checks
        ChatGatePrimaryAction.NONE -> null
    }
}
