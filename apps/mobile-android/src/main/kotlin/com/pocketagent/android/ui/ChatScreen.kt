package com.pocketagent.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.RuntimeUiState

@Composable
internal fun ChatScreenBody(
    state: ChatUiState,
    onSuggestedPrompt: (String) -> Unit,
    onGetReadyTapped: () -> Unit,
    onOpenModelSetup: () -> Unit,
    canLoadLastUsedModel: Boolean,
    lastUsedModelLabel: String?,
    onLoadLastUsedModel: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onRefreshRuntimeChecks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        OfflineAndStatusHeader(
            state = state,
            onGetReadyTapped = onGetReadyTapped,
            onOpenModelSetup = onOpenModelSetup,
            canLoadLastUsedModel = canLoadLastUsedModel,
            lastUsedModelLabel = lastUsedModelLabel,
            onLoadLastUsedModel = onLoadLastUsedModel,
            onOpenAdvanced = onOpenAdvanced,
            onRefreshRuntimeChecks = onRefreshRuntimeChecks,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MessageList(
            activeSession = state.activeSession,
            onSuggestedPrompt = onSuggestedPrompt,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("chat_message_list"),
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OfflineAndStatusHeader(
    state: ChatUiState,
    onGetReadyTapped: () -> Unit,
    onOpenModelSetup: () -> Unit,
    canLoadLastUsedModel: Boolean,
    lastUsedModelLabel: String?,
    onLoadLastUsedModel: () -> Unit,
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
    val backendLabel = state.runtime.runtimeBackend?.trim().orEmpty()

    @Composable
    fun StatusChips() {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                modifier = Modifier.testTag("offline_indicator"),
                onClick = { },
                label = { StatusChipLabel(stringResource(id = R.string.ui_offline_first)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
            AssistChip(
                onClick = { },
                label = {
                    StatusChipLabel(
                        text = stringResource(
                            id = R.string.ui_model_label,
                            state.runtime.routingMode.name,
                        ),
                    )
                },
            )
            AssistChip(
                onClick = { },
                label = {
                    StatusChipLabel(
                        text = "${stringResource(id = R.string.ui_speed_battery_title)}: ${state.runtime.performanceProfile.name}",
                    )
                },
            )
            AssistChip(
                onClick = { },
                label = {
                    StatusChipLabel(
                        text = stringResource(
                            id = R.string.ui_model_status_label,
                            modelStatusText,
                        ),
                    )
                },
            )
            if (backendLabel.isNotEmpty()) {
                AssistChip(
                    onClick = { },
                    label = {
                        StatusChipLabel(
                            text = stringResource(
                                id = R.string.ui_runtime_backend_label,
                                backendLabel,
                            ),
                        )
                    },
                )
            }
        }
    }

    StatusChips()

    state.runtime.modelStatusDetail
        ?.takeIf { it.isNotBlank() }
        ?.let { detail ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = detail,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("runtime_status_detail"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    if (backendLabel.equals("ADB_FALLBACK", ignoreCase = true)) {
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.testTag("runtime_backend_fallback_banner")) {
            Text(
                text = stringResource(id = R.string.ui_runtime_backend_fallback_debug_warning),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (state.runtime.lastErrorUserMessage != null && state.runtime.lastErrorCode != null) {
        val recoveryHint = stringResource(id = state.runtime.recoveryHintTextResId())
        val simpleFirstLocked = !state.advancedUnlocked
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.testTag("runtime_error_banner")) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(id = R.string.ui_runtime_error_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        id = R.string.ui_runtime_error_with_code,
                        state.runtime.lastErrorUserMessage,
                        state.runtime.lastErrorCode,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (showTechnicalDetails) {
                    state.runtime.lastErrorTechnicalDetail?.let { detail ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.runtime.modelRuntimeStatus == ModelRuntimeStatus.NOT_READY ||
                    state.runtime.modelRuntimeStatus == ModelRuntimeStatus.ERROR
                ) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = recoveryHint,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = if (simpleFirstLocked) onGetReadyTapped else onOpenModelSetup) {
                            Text(
                                stringResource(
                                    id = if (simpleFirstLocked) {
                                        R.string.ui_get_ready
                                    } else {
                                        R.string.ui_fix_model_setup
                                    },
                                ),
                            )
                        }
                        OutlinedButton(onClick = onRefreshRuntimeChecks) {
                            Text(stringResource(id = R.string.ui_refresh_runtime_checks))
                        }
                        if (canLoadLastUsedModel) {
                            OutlinedButton(onClick = onLoadLastUsedModel) {
                                Text(
                                    stringResource(
                                        id = R.string.ui_model_runtime_load_last_used_short,
                                        lastUsedModelLabel.orEmpty(),
                                    ),
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { showTechnicalDetails = !showTechnicalDetails },
                    ) {
                        Text(
                            text = stringResource(
                                id = if (showTechnicalDetails) {
                                    R.string.ui_hide_technical_details
                                } else {
                                    R.string.ui_show_technical_details
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    if (state.advancedUnlocked && state.showAdvancedUnlockCue) {
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.testTag("advanced_unlock_cue")) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(id = R.string.ui_advanced_unlocked_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(id = R.string.ui_advanced_unlocked_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenAdvanced) {
                    Text(stringResource(id = R.string.ui_open_advanced_controls))
                }
            }
        }
    }

    if (state.runtime.sendSlowState != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.testTag("runtime_send_slow_hint")) {
            Text(
                text = state.runtime.sendSlowState,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.runtime.lastError?.let { error ->
        if (state.runtime.lastErrorUserMessage != null) {
            return@let
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card {
            Text(
                text = error,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun RuntimeUiState.recoveryHintTextResId(): Int {
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
private fun StatusChipLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp),
    )
}

@Composable
private fun MessageList(
    activeSession: ChatSessionUiModel?,
    onSuggestedPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(activeSession?.id, activeSession?.messages?.size) {
        val messages = activeSession?.messages ?: return@LaunchedEffect
        if (messages.isNotEmpty()) {
            listState.scrollToItem(index = messages.lastIndex)
        }
    }

    if (activeSession == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(id = R.string.ui_no_session_selected),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (activeSession.messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.ui_chat_empty_state),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SuggestedPromptCard(
                    prompt = stringResource(id = R.string.ui_prompt_quick_answer),
                    onClick = onSuggestedPrompt,
                )
                SuggestedPromptCard(
                    prompt = stringResource(id = R.string.ui_prompt_image_help),
                    onClick = onSuggestedPrompt,
                )
                SuggestedPromptCard(
                    prompt = stringResource(id = R.string.ui_prompt_local_search),
                    onClick = onSuggestedPrompt,
                )
                SuggestedPromptCard(
                    prompt = stringResource(id = R.string.ui_prompt_reminder),
                    onClick = onSuggestedPrompt,
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = false,
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Top),
    ) {
        items(
            items = activeSession.messages,
            key = { it.id },
            contentType = { it.kind },
        ) { message ->
            val isUser = message.role == MessageRole.USER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                Surface(
                    tonalElevation = 1.dp,
                    color = when (message.role) {
                        MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
                        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
                        MessageRole.TOOL -> MaterialTheme.colorScheme.tertiaryContainer
                        MessageRole.SYSTEM -> MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .fillMaxWidth(0.9f),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        when {
                            message.imagePath != null -> {
                                Text(
                                    text = stringResource(id = R.string.ui_image_message_label, message.imagePath),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            message.toolName != null -> {
                                val toolStatus = message.interaction
                                    ?.toolCalls
                                    ?.firstOrNull()
                                    ?.status
                                val statusSuffix = toolStatus.toReadableSuffix()
                                Text(
                                    text = stringResource(id = R.string.ui_tool_message_label, message.toolName) + statusSuffix,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        val content = if (message.content.isBlank() && message.isStreaming) {
                            "..."
                        } else {
                            message.content
                        }
                        MarkdownMessageContent(content = content)
                        if (message.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(message.content)) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(id = R.string.a11y_copy_message),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PersistedToolCallStatus?.toReadableSuffix(): String {
    return when (this) {
        PersistedToolCallStatus.PENDING -> " (Pending)"
        PersistedToolCallStatus.RUNNING -> " (Running)"
        PersistedToolCallStatus.COMPLETED -> " (Completed)"
        PersistedToolCallStatus.FAILED -> " (Failed)"
        null -> ""
    }
}

@Composable
private fun SuggestedPromptCard(
    prompt: String,
    onClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(prompt) },
    ) {
        Text(
            text = prompt,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MarkdownMessageContent(content: String) {
    val codeFenceParts = remember(content) { content.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        codeFenceParts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = part.trim(),
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            } else {
                part.lines().forEach { rawLine ->
                    val line = rawLine.trimEnd()
                    if (line.isBlank()) return@forEach
                    val rendered = if (line.startsWith("- ") || line.startsWith("* ")) {
                        "• ${line.drop(2)}"
                    } else {
                        line
                    }
                    Text(
                        text = renderInlineBold(rendered),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun renderInlineBold(text: String): AnnotatedString {
    val regex = Regex("\\*\\*(.+?)\\*\\*")
    val matches = regex.findAll(text).toList()
    if (matches.isEmpty()) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        var cursor = 0
        matches.forEach { match ->
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(match.groupValues[1])
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

@Composable
internal fun ComposerBar(
    text: String,
    isSending: Boolean,
    isSendAllowed: Boolean,
    isModelActionsReady: Boolean,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onAttachImage: () -> Unit,
) {
    val sendStateDescription = when {
        isSending -> stringResource(id = R.string.a11y_send_state_sending)
        !isSendAllowed -> stringResource(id = R.string.a11y_send_state_runtime_not_ready)
        text.isBlank() -> stringResource(id = R.string.a11y_send_state_disabled)
        else -> stringResource(id = R.string.a11y_send_state_enabled)
    }
    val attachImageDescription = stringResource(id = R.string.a11y_attach_image)
    val sendButtonDescription = stringResource(id = R.string.a11y_send_button)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onAttachImage,
            enabled = !isSending && isModelActionsReady,
        ) {
            Icon(Icons.Default.Image, contentDescription = attachImageDescription)
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier
                .weight(1f)
                .testTag("composer_input"),
            label = { Text(stringResource(id = R.string.ui_composer_label)) },
            enabled = !isSending,
            maxLines = 4,
        )
        Button(
            modifier = Modifier
                .testTag("send_button")
                .semantics {
                    contentDescription = sendButtonDescription
                    stateDescription = sendStateDescription
                },
            onClick = if (isSending) onCancelSend else onSend,
            enabled = if (isSending) true else text.isNotBlank() && isSendAllowed,
        ) {
            Text(
                stringResource(
                    id = if (isSending) {
                        R.string.ui_cancel_button
                    } else {
                        R.string.ui_send_button
                    },
                ),
            )
        }
    }
}
