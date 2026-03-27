package com.pocketagent.android.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.theme.LocalReduceMotion
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.theme.PocketAgentDimensions
import com.pocketagent.android.ui.theme.tickConfirm
import com.pocketagent.android.ui.theme.tickLight
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.MessageUiModel
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.PersistedToolCallStatus
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun ChatScreenBody(
    state: ChatUiState,
    modelLoadingState: ModelLoadingState,
    onSuggestedPrompt: (String) -> Unit,
    onGetReadyTapped: () -> Unit,
    onOpenModels: () -> Unit,
    canLoadLastUsedModel: Boolean,
    lastUsedModelLabel: String?,
    onLoadLastUsedModel: () -> Unit,
    activeRuntimeModelLabel: String?,
    onRefresh: () -> Unit,
    onOpenToolDialog: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onCopiedToClipboard: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PocketAgentDimensions.screenPadding),
    ) {
        OfflineAndStatusHeader(
            state = state,
            modelLoadingState = modelLoadingState,
            onGetReadyTapped = onGetReadyTapped,
            onOpenModels = onOpenModels,
            canLoadLastUsedModel = canLoadLastUsedModel,
            lastUsedModelLabel = lastUsedModelLabel,
            onLoadLastUsedModel = onLoadLastUsedModel,
            activeRuntimeModelLabel = activeRuntimeModelLabel,
            onRefresh = onRefresh,
        )
        Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing).animateContentSize())
        MessageList(
            activeSession = state.activeSession,
            runtimeStatusDetail = state.runtime.modelStatusDetail,
            onSuggestedPrompt = onSuggestedPrompt,
            onOpenToolDialog = onOpenToolDialog,
            onEditMessage = onEditMessage,
            onRegenerateMessage = onRegenerateMessage,
            onCopiedToClipboard = onCopiedToClipboard,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("chat_message_list"),
        )
    }
}

@Composable
private fun MessageList(
    activeSession: ChatSessionUiModel?,
    runtimeStatusDetail: String?,
    onSuggestedPrompt: (String) -> Unit,
    onOpenToolDialog: () -> Unit,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val reduceMotion = LocalReduceMotion.current
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

    LaunchedEffect(activeSession?.id, activeSession?.messages?.size, latestMessage?.content, latestMessage?.isStreaming) {
        val messages = activeSession?.messages ?: return@LaunchedEffect
        if (messages.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(index = messages.lastIndex)
        }
    }

    // Dismiss keyboard when user scrolls the chat list
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            keyboardController?.hide()
        }
    }

    // M9: Announce streaming state changes for screen readers
    val view = LocalView.current
    val assistantRespondingAnnouncement = stringResource(id = R.string.a11y_assistant_responding)
    val responseCompleteAnnouncement = stringResource(id = R.string.a11y_response_complete)
    LaunchedEffect(latestMessage?.isStreaming) {
        if (latestMessage?.role == MessageRole.ASSISTANT) {
            if (latestMessage.isStreaming) {
                view.announceForAccessibility(assistantRespondingAnnouncement)
            } else if (latestMessage.content.isNotBlank()) {
                view.announceForAccessibility(responseCompleteAnnouncement)
            }
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
                ShimmerMessageLoadingPlaceholder()
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
                    modifier = Modifier.padding(horizontal = PocketAgentDimensions.screenPadding * 2),
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(compactSpacing))
                    Text(
                        text = stringResource(id = R.string.ui_chat_empty_state),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing))
                    val prompts = listOf(
                        stringResource(id = R.string.ui_prompt_quick_answer),
                        stringResource(id = R.string.ui_prompt_image_help),
                        stringResource(id = R.string.ui_prompt_local_search),
                        stringResource(id = R.string.ui_prompt_reminder),
                    )
                    prompts.forEachIndexed { index, prompt ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                            ) + slideInVertically(
                                initialOffsetY = { it / 2 },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow,
                                ),
                            ),
                        ) {
                            SuggestedPromptCard(prompt = prompt, onClick = onSuggestedPrompt)
                        }
                    }
                    Spacer(modifier = Modifier.height(compactSpacing))
                    TextButton(onClick = onOpenToolDialog) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(compactSpacing))
                        Text(
                            text = stringResource(id = R.string.ui_local_tools_title),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        return
    }

    val messages = activeSession.messages
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = false,
            contentPadding = PaddingValues(
                top = compactSpacing,
                bottom = PocketAgentDimensions.screenPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(compactSpacing / 2, alignment = Alignment.Top),
        ) {
            items(
                count = messages.size,
                key = { messages[it].id },
                contentType = { messages[it].kind },
            ) { index ->
                val message = messages[index]
                val prevMessage = messages.getOrNull(index - 1)
                val nextMessage = messages.getOrNull(index + 1)
                val isFirstInGroup = prevMessage?.role != message.role
                val isLastInGroup = nextMessage?.role != message.role

                // M4: Timestamp separator when time gap > 5 min
                if (isFirstInGroup && message.timestampEpochMs > 0L) {
                    val showTimestamp = prevMessage == null ||
                        (message.timestampEpochMs - prevMessage.timestampEpochMs > 5 * 60 * 1000)
                    if (showTimestamp) {
                        TimestampSeparator(epochMs = message.timestampEpochMs)
                    }
                }

                // M1: Entrance animation (skipped when reduce motion enabled)
                val bubbleModifier = Modifier
                    .animateItem()
                    .padding(
                        top = if (isFirstInGroup) compactSpacing + (compactSpacing / 2) else 0.dp,
                        bottom = if (isLastInGroup) compactSpacing + (compactSpacing / 2) else 0.dp,
                    )

                if (reduceMotion) {
                    MessageBubble(
                        message = message,
                        runtimeStatusDetail = runtimeStatusDetail,
                        onEditMessage = onEditMessage,
                        onRegenerateMessage = onRegenerateMessage,
                        onCopiedToClipboard = onCopiedToClipboard,
                        clipboardManager = clipboardManager,
                        isFirstInGroup = isFirstInGroup,
                        isLastInGroup = isLastInGroup,
                        modifier = bubbleModifier,
                    )
                } else {
                    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = fadeIn(tween(PocketAgentDimensions.animNormal)) + slideInVertically(
                            initialOffsetY = { it / 4 },
                            animationSpec = tween(PocketAgentDimensions.animNormal),
                        ),
                    ) {
                        MessageBubble(
                            message = message,
                            runtimeStatusDetail = runtimeStatusDetail,
                            onEditMessage = onEditMessage,
                            onRegenerateMessage = onRegenerateMessage,
                            onCopiedToClipboard = onCopiedToClipboard,
                            clipboardManager = clipboardManager,
                            isFirstInGroup = isFirstInGroup,
                            isLastInGroup = isLastInGroup,
                            modifier = bubbleModifier,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !isNearBottom,
            modifier = Modifier.align(Alignment.BottomEnd).padding(PocketAgentDimensions.screenPadding),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            FloatingActionButton(
                onClick = { coroutineScope.launch { listState.animateScrollToItem(activeSession.messages.lastIndex) } },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = stringResource(id = R.string.a11y_scroll_to_latest),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageUiModel,
    runtimeStatusDetail: String?,
    onEditMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCopiedToClipboard: () -> Unit,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val isUser = message.role == MessageRole.USER
    var showContextMenu by remember { mutableStateOf(false) }

    // M3: Grouped corner radii -- flatten inner corners for consecutive same-role messages
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val cornerRadius = PocketAgentDimensions.messageBubblePadding
    val smallCorner = compactSpacing
    val bubbleShape = when {
        isFirstInGroup && isLastInGroup -> RoundedCornerShape(cornerRadius)
        isUser && isFirstInGroup -> RoundedCornerShape(cornerRadius, cornerRadius, smallCorner, cornerRadius)
        isUser && isLastInGroup -> RoundedCornerShape(cornerRadius, smallCorner, cornerRadius, cornerRadius)
        isUser -> RoundedCornerShape(cornerRadius, smallCorner, smallCorner, cornerRadius)
        !isUser && isFirstInGroup -> RoundedCornerShape(cornerRadius, cornerRadius, cornerRadius, smallCorner)
        !isUser && isLastInGroup -> RoundedCornerShape(smallCorner, cornerRadius, cornerRadius, cornerRadius)
        else -> RoundedCornerShape(smallCorner, cornerRadius, cornerRadius, smallCorner)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            tonalElevation = 1.dp,
            color = when (message.role) {
                MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
                MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
                MessageRole.TOOL -> MaterialTheme.colorScheme.secondaryContainer
                MessageRole.SYSTEM -> MaterialTheme.colorScheme.errorContainer
            },
            modifier = Modifier.clip(bubbleShape).fillMaxWidth(0.9f),
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
                        SelectionContainer {
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
    Spacer(modifier = Modifier.height(PocketAgentDimensions.sectionSpacing / 2))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        message.tokensPerSec?.let { Text(stringResource(id = R.string.ui_metric_tokens_per_sec, it), style = style, color = color) }
        message.firstTokenMs?.let { Text(stringResource(id = R.string.ui_metric_ttft, it), style = style, color = color) }
        message.totalLatencyMs?.let { Text(stringResource(id = R.string.ui_metric_total_latency, it), style = style, color = color) }
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
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
) {
    if (message.content.isBlank() || message.isStreaming) return
    val haptic = LocalHapticFeedback.current
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
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(id = R.string.a11y_copy_message), modifier = Modifier.size(18.dp))
        }
        if (message.role == MessageRole.USER) {
            IconButton(
                onClick = {
                    haptic.tickLight()
                    onEditMessage(message.id)
                },
            ) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(id = R.string.a11y_edit_message), modifier = Modifier.size(18.dp))
            }
        }
        if (message.role == MessageRole.ASSISTANT) {
            IconButton(
                onClick = {
                    haptic.tickLight()
                    onRegenerateMessage(message.id)
                },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(id = R.string.a11y_regenerate_response), modifier = Modifier.size(18.dp))
            }
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
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
) {
    val haptic = LocalHapticFeedback.current
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(id = R.string.ui_copy)) },
            onClick = {
                haptic.tickLight()
                clipboardManager.setText(AnnotatedString(message.content))
                onCopiedToClipboard()
                onDismiss()
            },
        )
        if (message.role == MessageRole.USER) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(id = R.string.ui_edit)) },
                onClick = {
                    onEditMessage(message.id)
                    onDismiss()
                },
            )
        }
        if (message.role == MessageRole.ASSISTANT) {
            androidx.compose.material3.DropdownMenuItem(
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
private fun ThinkingBubble(reasoningContent: String) {
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
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(id = if (expanded) R.string.a11y_collapse_thinking else R.string.a11y_expand_thinking),
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
private fun LoadingDotsAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading_dots")
    val compactSpacing = PocketAgentDimensions.sectionSpacing / 2
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(PocketAgentDimensions.animSlow, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "dot1",
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(PocketAgentDimensions.animSlow, delayMillis = PocketAgentDimensions.animFast, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "dot2",
    )
    val alpha3 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(PocketAgentDimensions.animSlow, delayMillis = 400, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
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
private fun ThinkingInProgressIndicator() {
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
private fun PersistedToolCallStatus?.toReadableSuffix(): String {
    return when (this) {
        PersistedToolCallStatus.PENDING -> stringResource(id = R.string.ui_tool_call_status_pending_suffix)
        PersistedToolCallStatus.RUNNING -> stringResource(id = R.string.ui_tool_call_status_running_suffix)
        PersistedToolCallStatus.COMPLETED -> stringResource(id = R.string.ui_tool_call_status_completed_suffix)
        PersistedToolCallStatus.FAILED -> stringResource(id = R.string.ui_tool_call_status_failed_suffix)
        null -> ""
    }
}

@Composable
private fun TimestampSeparator(epochMs: Long) {
    val relativeTime = remember(epochMs) {
        DateUtils.getRelativeTimeSpanString(
            epochMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = PocketAgentDimensions.sectionSpacing),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = relativeTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuggestedPromptCard(
    prompt: String,
    onClick: (String) -> Unit,
) {
    Surface(
        onClick = { onClick(prompt) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = prompt,
            modifier = Modifier.padding(PocketAgentDimensions.cardPadding),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AttachmentThumbnailRow(
    attachmentPaths: List<String>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PocketAgentDimensions.sectionSpacing),
    ) {
        attachmentPaths.take(3).forEach { path ->
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(File(path))
                    .crossfade(PocketAgentDimensions.animNormal)
                    .build(),
                contentDescription = stringResource(id = R.string.ui_image_message_label, path),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp).clip(MaterialTheme.shapes.small),
            )
        }
    }
}

internal fun shouldRenderInThreadLoadingPlaceholder(message: MessageUiModel): Boolean {
    return message.role == MessageRole.ASSISTANT && message.isStreaming && message.content.isBlank()
}
