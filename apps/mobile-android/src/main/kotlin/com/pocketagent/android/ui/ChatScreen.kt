package com.pocketagent.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ChatGateState
import com.pocketagent.android.ui.state.ChatGateStatus
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import com.pocketagent.android.ui.state.RuntimeUiState
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun ChatScreenBody(
    state: ChatUiState,
    onSuggestedPrompt: (String) -> Unit,
    onGetReadyTapped: () -> Unit,
    onOpenModelSetup: () -> Unit,
    canLoadLastUsedModel: Boolean,
    lastUsedModelLabel: String?,
    onLoadLastUsedModel: () -> Unit,
    activeRuntimeModelLabel: String?,
    activeRuntimeModelSourceLabel: String?,
    onOpenAdvanced: () -> Unit,
    onRefreshRuntimeChecks: () -> Unit,
    onEditMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
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
            activeRuntimeModelLabel = activeRuntimeModelLabel,
            activeRuntimeModelSourceLabel = activeRuntimeModelSourceLabel,
            onOpenAdvanced = onOpenAdvanced,
            onRefreshRuntimeChecks = onRefreshRuntimeChecks,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MessageList(
            activeSession = state.activeSession,
            runtimeStatusDetail = state.runtime.modelStatusDetail,
            onSuggestedPrompt = onSuggestedPrompt,
            onEditMessage = onEditMessage,
            onRegenerateMessage = onRegenerateMessage,
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
    activeRuntimeModelLabel: String?,
    activeRuntimeModelSourceLabel: String?,
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
    val backendProfileLabel = resolveBackendProfileLabel(state.runtime.backendProfile)
    val compiledBackendsLabel = state.runtime.compiledBackend?.trim().orEmpty()

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
            if (!activeRuntimeModelLabel.isNullOrBlank()) {
                AssistChip(
                    onClick = { },
                    label = {
                        StatusChipLabel(
                            text = stringResource(
                                id = R.string.ui_active_runtime_model_chip,
                                activeRuntimeModelLabel,
                            ),
                        )
                    },
                )
            }
            if (!activeRuntimeModelSourceLabel.isNullOrBlank()) {
                AssistChip(
                    onClick = { },
                    label = {
                        StatusChipLabel(
                            text = stringResource(
                                id = R.string.ui_active_runtime_source_chip,
                                activeRuntimeModelSourceLabel,
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
    val showBackendDiagnostics = backendProfileLabel.isNotEmpty() ||
        compiledBackendsLabel.isNotEmpty() ||
        state.runtime.nativeRuntimeSupported != null ||
        state.runtime.strictAcceleratorFailFast != null ||
        state.runtime.autoBackendCpuFallback != null
    if (showBackendDiagnostics) {
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.testTag("runtime_backend_diagnostics_card")) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(id = R.string.ui_runtime_technical_details_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (backendProfileLabel.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            id = R.string.ui_runtime_technical_backend_profile,
                            backendProfileLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (compiledBackendsLabel.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            id = R.string.ui_runtime_technical_compiled_backends,
                            compiledBackendsLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.runtime.nativeRuntimeSupported?.let { supported ->
                    Text(
                        text = stringResource(
                            id = R.string.ui_runtime_technical_native_support,
                            if (supported) {
                                stringResource(id = R.string.ui_runtime_technical_yes)
                            } else {
                                stringResource(id = R.string.ui_runtime_technical_no)
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.runtime.strictAcceleratorFailFast?.let { strict ->
                    Text(
                        text = stringResource(
                            id = R.string.ui_runtime_technical_strict_policy,
                            if (strict) {
                                stringResource(id = R.string.ui_runtime_technical_yes)
                            } else {
                                stringResource(id = R.string.ui_runtime_technical_no)
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.runtime.autoBackendCpuFallback?.let { fallback ->
                    Text(
                        text = stringResource(
                            id = R.string.ui_runtime_technical_auto_fallback,
                            if (fallback) {
                                stringResource(id = R.string.ui_runtime_technical_yes)
                            } else {
                                stringResource(id = R.string.ui_runtime_technical_no)
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
                                val label = lastUsedModelLabel?.takeIf { it.isNotBlank() }
                                Text(
                                    if (label != null) {
                                        stringResource(
                                            id = R.string.ui_model_runtime_load_last_used_short,
                                            label,
                                        )
                                    } else {
                                        stringResource(id = R.string.ui_model_runtime_load_last_used)
                                    },
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

@Composable
private fun resolveBackendProfileLabel(profile: String?): String {
    val normalized = profile?.trim()?.lowercase().orEmpty()
    if (normalized.isEmpty()) {
        return ""
    }
    return when (normalized) {
        "auto" -> stringResource(id = R.string.ui_gpu_backend_auto)
        "hexagon" -> stringResource(id = R.string.ui_gpu_backend_hexagon)
        "opencl" -> stringResource(id = R.string.ui_gpu_backend_opencl)
        "cpu" -> stringResource(id = R.string.ui_gpu_backend_cpu)
        else -> profile.orEmpty()
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
    runtimeStatusDetail: String?,
    onSuggestedPrompt: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val latestMessage = activeSession?.messages?.lastOrNull()
    val isNearBottom by remember(listState, activeSession?.id) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) {
                true
            } else {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= layoutInfo.totalItemsCount - 2
            }
        }
    }

    LaunchedEffect(activeSession?.id) {
        val messages = activeSession?.messages ?: return@LaunchedEffect
        if (messages.isNotEmpty()) {
            listState.scrollToItem(index = messages.lastIndex)
        }
    }

    LaunchedEffect(
        activeSession?.id,
        activeSession?.messages?.size,
        latestMessage?.content,
        latestMessage?.isStreaming,
    ) {
        val messages = activeSession?.messages ?: return@LaunchedEffect
        if (messages.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(index = messages.lastIndex)
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
            if (!activeSession.messagesLoaded) {
                Text(
                    text = "Loading conversation...",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
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
        }
        return
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = false,
            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Top),
        ) {
            items(
                items = activeSession.messages,
                key = { it.id },
                contentType = { it.kind },
            ) { message ->
                MessageBubble(
                    message = message,
                    runtimeStatusDetail = runtimeStatusDetail,
                    clipboardManager = clipboardManager,
                    onEditMessage = onEditMessage,
                    onRegenerateMessage = onRegenerateMessage,
                )
            }
        }
        if (!isNearBottom) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(activeSession.messages.lastIndex)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = "Scroll to latest message",
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageUiModel,
    runtimeStatusDetail: String?,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
) {
    val isUser = message.role == MessageRole.USER
    var showContextMenu by remember { mutableStateOf(false) }

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
            Box {
                Column(modifier = Modifier
                    .clickable { if (message.content.isNotBlank()) showContextMenu = true }
                    .padding(12.dp),
                ) {
                    // Image/tool header labels
                    val attachmentPaths = message.imagePaths.ifEmpty {
                        listOfNotNull(message.imagePath)
                    }
                    when {
                        attachmentPaths.isNotEmpty() -> {
                            Text(
                                text = "${attachmentPaths.size} image(s) attached",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            AttachmentThumbnailRow(attachmentPaths = attachmentPaths)
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

                    // Thinking/reasoning content (collapsible)
                    if (!message.reasoningContent.isNullOrBlank()) {
                        ThinkingBubble(reasoningContent = message.reasoningContent)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Main content
                    val content = if (message.content.isBlank() && message.isStreaming) {
                        "..."
                    } else {
                        message.content
                    }
                    if (shouldRenderInThreadLoadingPlaceholder(message)) {
                        LoadingDotsAnimation()
                        runtimeStatusDetail
                            ?.takeIf { it.isNotBlank() }
                            ?.let { detail ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    } else {
                        MarkdownMessageContent(content = content, clipboardManager = clipboardManager)
                    }

                    // Generation metadata (for assistant messages)
                    if (message.role == MessageRole.ASSISTANT && !message.isStreaming && message.content.isNotBlank()) {
                        val hasMetadata = message.tokensPerSec != null || message.firstTokenMs != null || message.totalLatencyMs != null
                        if (hasMetadata) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                message.tokensPerSec?.let { tps ->
                                    Text(
                                        text = "${"%.1f".format(tps)} tok/s",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                message.firstTokenMs?.let { ttft ->
                                    Text(
                                        text = "TTFT: ${ttft}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                message.totalLatencyMs?.let { total ->
                                    Text(
                                        text = "Total: ${total}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // Action buttons row
                    if (message.content.isNotBlank() && !message.isStreaming) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
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
                            if (message.role == MessageRole.USER) {
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { onEditMessage(message.id) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit message",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            if (message.role == MessageRole.ASSISTANT) {
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { onRegenerateMessage(message.id) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Regenerate response",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Context menu (dropdown)
                androidx.compose.material3.DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            showContextMenu = false
                        },
                    )
                    if (message.role == MessageRole.USER) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                onEditMessage(message.id)
                                showContextMenu = false
                            },
                        )
                    }
                    if (message.role == MessageRole.ASSISTANT) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Regenerate") },
                            onClick = {
                                onRegenerateMessage(message.id)
                                showContextMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBubble(reasoningContent: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse thinking" else "Expand thinking",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = reasoningContent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = reasoningContent.take(80) + if (reasoningContent.length > 80) "..." else "",
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
private fun LoadingDotsAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading_dots")
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot1",
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot2",
    )
    val alpha3 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot3",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text("●", color = dotColor.copy(alpha = alpha1), style = MaterialTheme.typography.bodyLarge)
        Text("●", color = dotColor.copy(alpha = alpha2), style = MaterialTheme.typography.bodyLarge)
        Text("●", color = dotColor.copy(alpha = alpha3), style = MaterialTheme.typography.bodyLarge)
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
private fun MarkdownMessageContent(
    content: String,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager? = null,
) {
    val sanitizedContent = remember(content) { sanitizeMarkdownForRendering(content) }
    val codeFenceParts = remember(sanitizedContent) { sanitizedContent.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        codeFenceParts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                // Code block with language label and copy button
                val lines = part.lines()
                val language = lines.firstOrNull()?.trim().orEmpty()
                val codeContent = if (language.isNotBlank() && !language.contains(" ")) {
                    lines.drop(1).joinToString("\n").trim()
                } else {
                    part.trim()
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        // Code block header with language + copy button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (language.isNotBlank() && !language.contains(" ")) {
                                Text(
                                    text = language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Spacer(modifier = Modifier.width(1.dp))
                            }
                            if (clipboardManager != null) {
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(codeContent)) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy code",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = codeContent,
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .padding(bottom = 10.dp)
                                .horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            } else {
                part.lines().forEach { rawLine ->
                    val line = rawLine.trimEnd()
                    if (line.isBlank()) return@forEach
                    // Heading support
                    when {
                        line.startsWith("### ") -> {
                            MarkdownInlineText(
                                text = renderInlineMarkdown(line.drop(4)),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        line.startsWith("## ") -> {
                            MarkdownInlineText(
                                text = renderInlineMarkdown(line.drop(3)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        line.startsWith("# ") -> {
                            MarkdownInlineText(
                                text = renderInlineMarkdown(line.drop(2)),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        line.startsWith("> ") -> {
                            // Blockquote
                            Row {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(20.dp),
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                                MarkdownInlineText(
                                    text = renderInlineMarkdown(line.drop(2)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        line.startsWith("- ") || line.startsWith("* ") -> {
                            MarkdownInlineText(
                                text = renderInlineMarkdown("• ${line.drop(2)}"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        line.matches(Regex("^\\d+\\.\\s.*")) -> {
                            // Ordered list
                            MarkdownInlineText(
                                text = renderInlineMarkdown(line),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        line.startsWith("---") || line.startsWith("***") -> {
                            // Horizontal rule
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        else -> {
                            MarkdownInlineText(
                                text = renderInlineMarkdown(line),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val INLINE_MARKDOWN_REGEX = Regex(
    """(\*\*(.+?)\*\*)|(\*(.+?)\*)|(`(.+?)`)|(\~\~(.+?)\~\~)|(\[(.+?)\]\((.+?)\))""",
)

private fun renderInlineMarkdown(text: String): AnnotatedString {
    val matches = INLINE_MARKDOWN_REGEX.findAll(text).toList()
    if (matches.isEmpty()) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        var cursor = 0
        matches.forEach { match ->
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            when {
                // Bold: **text**
                match.groupValues[2].isNotEmpty() -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[2])
                    }
                }
                // Italic: *text*
                match.groupValues[4].isNotEmpty() -> {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(match.groupValues[4])
                    }
                }
                // Inline code: `text`
                match.groupValues[6].isNotEmpty() -> {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = androidx.compose.ui.graphics.Color(0x20808080),
                    )) {
                        append(match.groupValues[6])
                    }
                }
                // Strikethrough: ~~text~~
                match.groupValues[8].isNotEmpty() -> {
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                        append(match.groupValues[8])
                    }
                }
                // Link: [text](url)
                match.groupValues[10].isNotEmpty() -> {
                    pushStringAnnotation(
                        tag = URL_ANNOTATION_TAG,
                        annotation = match.groupValues[11],
                    )
                    withStyle(SpanStyle(
                        color = androidx.compose.ui.graphics.Color(0xFF1A73E8),
                        textDecoration = TextDecoration.Underline,
                    )) {
                        append(match.groupValues[10])
                    }
                    pop()
                }
                else -> append(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

@Composable
private fun MarkdownInlineText(
    text: AnnotatedString,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = text,
        style = style.merge(
            TextStyle(
                color = color,
                fontWeight = fontWeight,
            ),
        ),
        onClick = { offset ->
            text.getStringAnnotations(
                tag = URL_ANNOTATION_TAG,
                start = offset,
                end = offset,
            ).firstOrNull()?.let { annotation ->
                uriHandler.openUri(annotation.item)
            }
        },
    )
}

@Composable
private fun AttachmentThumbnailRow(
    attachmentPaths: List<String>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachmentPaths.take(3).forEach { path ->
            AsyncImage(
                model = File(path),
                contentDescription = stringResource(id = R.string.ui_image_message_label, path),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        }
    }
}

private fun sanitizeMarkdownForRendering(content: String): String {
    val codeFenceCount = "```".toRegex().findAll(content).count()
    return if (codeFenceCount % 2 == 0) {
        content
    } else {
        "$content\n```"
    }
}

private const val URL_ANNOTATION_TAG = "url"

@Composable
internal fun ComposerBar(
    text: String,
    isSending: Boolean,
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
    onOpenCompletionSettings: () -> Unit = {},
    onBlockedAction: (ChatGatePrimaryAction) -> Unit,
) {
    val isEditing = editingMessageId != null
    val canTriggerBlockedAction = hasChatGatePrimaryAction(chatGateState)
    val canTriggerUserAction = chatGateState.isReady || canTriggerBlockedAction
    val sendStateDescription = when {
        isSending -> stringResource(id = R.string.a11y_send_state_sending)
        !chatGateState.isReady -> stringResource(id = R.string.a11y_send_state_runtime_not_ready)
        text.isBlank() -> stringResource(id = R.string.a11y_send_state_disabled)
        else -> stringResource(id = R.string.a11y_send_state_enabled)
    }
    val attachImageDescription = stringResource(id = R.string.a11y_attach_image)
    val sendButtonDescription = stringResource(id = R.string.a11y_send_button)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (shouldShowChatGateInlineCard(chatGateState)) {
            ChatGateInlineCard(
                chatGateState = chatGateState,
                onPrimaryAction = onBlockedAction,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Edit mode indicator
        if (isEditing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
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

        // Image preview strip
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
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = path.substringAfterLast('/').take(20),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
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
                    if (chatGateState.isReady) {
                        onAttachImage()
                    } else {
                        onBlockedAction(chatGateState.primaryAction)
                    }
                },
                enabled = !isSending && canTriggerUserAction,
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
            // Completion settings button
            IconButton(
                onClick = onOpenCompletionSettings,
                modifier = Modifier.size(36.dp),
            ) {
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
                    when {
                        isSending -> onCancelSend()
                        isEditing -> onSubmitEdit()
                        chatGateState.isReady -> onSend()
                        else -> onBlockedAction(chatGateState.primaryAction)
                    }
                },
                enabled = if (isSending) true else text.isNotBlank() && canTriggerUserAction,
            ) {
                Text(
                    when {
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
private fun ChatGateInlineCard(
    chatGateState: ChatGateState,
    onPrimaryAction: (ChatGatePrimaryAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_gate_inline_card"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = chatGateInlineMessage(chatGateState = chatGateState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            chatGatePrimaryActionLabelResId(chatGateState.primaryAction)?.let { labelId ->
                OutlinedButton(
                    onClick = { onPrimaryAction(chatGateState.primaryAction) },
                ) {
                    Text(stringResource(id = labelId))
                }
            }
        }
    }
}

@Composable
private fun chatGateInlineMessage(chatGateState: ChatGateState): String {
    return when (chatGateState.status) {
        ChatGateStatus.READY -> ""
        ChatGateStatus.BLOCKED_MODEL_MISSING -> stringResource(id = R.string.ui_chat_gate_blocked_model_missing)
        ChatGateStatus.BLOCKED_RUNTIME_CHECK -> stringResource(id = R.string.ui_chat_gate_blocked_runtime_check)
        ChatGateStatus.LOADING_MODEL -> chatGateState.detail
            ?.takeIf { detail -> detail.isNotBlank() }
            ?: stringResource(id = R.string.ui_chat_gate_loading)
        ChatGateStatus.ERROR_RECOVERABLE -> chatGateState.detail
            ?.takeIf { detail -> detail.isNotBlank() }
            ?: stringResource(id = R.string.ui_chat_gate_recoverable)
    }
}

internal fun shouldShowChatGateInlineCard(chatGateState: ChatGateState): Boolean {
    return chatGateState.status != ChatGateStatus.READY
}

internal fun shouldRenderInThreadLoadingPlaceholder(message: MessageUiModel): Boolean {
    return message.role == MessageRole.ASSISTANT &&
        message.isStreaming &&
        message.content.isBlank()
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
