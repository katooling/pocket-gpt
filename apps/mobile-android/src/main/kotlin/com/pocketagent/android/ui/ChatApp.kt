package com.pocketagent.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.RoutingMode
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.MessageRole
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketAgentApp(
    viewModel: ChatViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(
        initialValue = if (state.isSessionDrawerOpen) DrawerValue.Open else DrawerValue.Closed,
    )
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.attachImage(it.toString()) }
    }

    LaunchedEffect(state.isSessionDrawerOpen) {
        if (state.isSessionDrawerOpen) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
                SessionDrawer(
                    state = state,
                    onCreateSession = viewModel::createSession,
                    onSwitchSession = viewModel::switchSession,
                    onDeleteSession = viewModel::deleteSession,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(id = R.string.ui_app_title),
                            modifier = Modifier.semantics { heading() },
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            modifier = Modifier.testTag("session_drawer_button"),
                            onClick = {
                                viewModel.setSessionDrawerOpen(true)
                                scope.launch { drawerState.open() }
                            },
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(id = R.string.a11y_session_drawer_open),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            modifier = Modifier.testTag("tool_dialog_button"),
                            onClick = { viewModel.setToolDialogOpen(true) },
                        ) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = stringResource(id = R.string.a11y_tool_actions_open),
                            )
                        }
                        IconButton(
                            modifier = Modifier.testTag("advanced_sheet_button"),
                            onClick = { viewModel.setAdvancedSheetOpen(true) },
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(id = R.string.a11y_advanced_controls_open),
                            )
                        }
                        IconButton(
                            modifier = Modifier.testTag("privacy_sheet_button"),
                            onClick = { viewModel.setPrivacySheetOpen(true) },
                        ) {
                            Icon(
                                Icons.Default.PrivacyTip,
                                contentDescription = stringResource(id = R.string.a11y_privacy_controls_open),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                ComposerBar(
                    text = state.composer.text,
                    isSending = state.composer.isSending,
                    onTextChanged = viewModel::onComposerChanged,
                    onSend = viewModel::sendMessage,
                    onAttachImage = { imagePicker.launch("image/*") },
                )
            },
        ) { innerPadding ->
            ChatScreenBody(
                state = state,
                onSuggestedPrompt = viewModel::prefillComposer,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (state.isToolDialogOpen) {
        ToolDialog(
            onDismiss = { viewModel.setToolDialogOpen(false) },
            onUsePrompt = viewModel::prefillComposer,
        )
    }

    if (state.isPrivacySheetOpen) {
        val privacySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.setPrivacySheetOpen(false) },
            sheetState = privacySheetState,
        ) {
            PrivacyInfoSheet(onClose = { viewModel.setPrivacySheetOpen(false) })
        }
    }

    if (state.isAdvancedSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.setAdvancedSheetOpen(false) },
            sheetState = sheetState,
        ) {
            AdvancedSettingsSheet(
                state = state,
                onRoutingModeSelected = viewModel::setRoutingMode,
                onExportDiagnostics = viewModel::exportDiagnostics,
            )
        }
    }

    if (state.showOnboarding) {
        OnboardingOverlay(
            page = state.onboardingPage,
            onNext = viewModel::nextOnboardingPage,
            onSkip = viewModel::skipOnboarding,
            onFinish = viewModel::completeOnboarding,
        )
    }
}

@Composable
private fun SessionDrawer(
    state: ChatUiState,
    onCreateSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val createSessionDescription = stringResource(id = R.string.a11y_create_session)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = R.string.ui_sessions_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onCreateSession) {
            Icon(Icons.Default.Add, contentDescription = createSessionDescription)
        }
    }
    HorizontalDivider()
    if (state.sessions.isEmpty()) {
        Text(
            text = stringResource(id = R.string.ui_no_sessions_yet),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.sessions.forEach { session ->
        val isActive = session.id == state.activeSessionId
        val activeStateDescription = if (isActive) {
            stringResource(id = R.string.a11y_session_active)
        } else {
            stringResource(id = R.string.a11y_session_inactive)
        }
        val switchSessionDescription = stringResource(
            id = R.string.a11y_switch_session,
            session.title,
        )
        val deleteSessionDescription = stringResource(
            id = R.string.a11y_delete_session,
            session.title,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .semantics {
                    selected = isActive
                    stateDescription = activeStateDescription
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                modifier = Modifier.semantics {
                    contentDescription = switchSessionDescription
                },
                onClick = { onSwitchSession(session.id) },
            ) {
                Text(
                    text = session.title,
                    maxLines = 1,
                    color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                modifier = Modifier.semantics {
                    contentDescription = deleteSessionDescription
                },
                onClick = { onDeleteSession(session.id) },
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ChatScreenBody(
    state: ChatUiState,
    onSuggestedPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp)) {
        OfflineAndStatusHeader(state = state)
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
private fun OfflineAndStatusHeader(state: ChatUiState) {
    val modelStatusText = when (state.runtime.modelRuntimeStatus) {
        ModelRuntimeStatus.NOT_READY -> stringResource(id = R.string.ui_model_status_not_ready)
        ModelRuntimeStatus.LOADING -> stringResource(id = R.string.ui_model_status_loading)
        ModelRuntimeStatus.READY -> stringResource(id = R.string.ui_model_status_ready)
        ModelRuntimeStatus.ERROR -> stringResource(id = R.string.ui_model_status_error)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            modifier = Modifier.testTag("offline_indicator"),
            onClick = { },
            label = { Text(stringResource(id = R.string.ui_offline_first)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )
        AssistChip(
            onClick = { },
            label = {
                Text(
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
                Text(
                    text = stringResource(
                        id = R.string.ui_model_status_label,
                        modelStatusText,
                    ),
                )
            },
        )
    }

    if (state.runtime.lastErrorUserMessage != null && state.runtime.lastErrorCode != null) {
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
                state.runtime.lastErrorTechnicalDetail?.let { detail ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
private fun MessageList(
    activeSession: ChatSessionUiModel?,
    onSuggestedPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(activeSession?.messages?.size, activeSession?.messages?.lastOrNull()?.content) {
        val messages = activeSession?.messages ?: return@LaunchedEffect
        if (messages.isNotEmpty()) {
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                Text(
                                    text = stringResource(id = R.string.ui_tool_message_label, message.toolName),
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
private fun ComposerBar(
    text: String,
    isSending: Boolean,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
) {
    val sendStateDescription = when {
        isSending -> stringResource(id = R.string.a11y_send_state_sending)
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
        IconButton(onClick = onAttachImage) {
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
            onClick = onSend,
            enabled = text.isNotBlank() && !isSending,
        ) {
            Text(stringResource(id = R.string.ui_send_button))
        }
    }
}

@Composable
private fun ToolDialog(
    onDismiss: () -> Unit,
    onUsePrompt: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.ui_local_tools_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(id = R.string.ui_tool_prompt_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onUsePrompt("calculate 4*9") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.ui_tool_calculator_prompt))
                }
                Button(
                    onClick = { onUsePrompt("what time is it") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.ui_tool_date_time_prompt))
                }
                Button(
                    onClick = { onUsePrompt("search launch checklist") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.ui_tool_local_search_prompt))
                }
                Button(
                    onClick = { onUsePrompt("find notes runtime gate") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.ui_tool_notes_prompt))
                }
                Button(
                    onClick = { onUsePrompt("remind me to run QA closeout") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(id = R.string.ui_tool_reminder_prompt))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.ui_close)) }
        },
    )
}

@Composable
private fun OnboardingOverlay(
    page: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    val pages = listOf(
        stringResource(id = R.string.ui_onboarding_page_1),
        stringResource(id = R.string.ui_onboarding_page_2),
        stringResource(id = R.string.ui_onboarding_page_3),
    )
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(id = R.string.ui_onboarding_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pages[page.coerceIn(0, pages.lastIndex)])
                Text(
                    text = stringResource(id = R.string.ui_onboarding_page_counter, page + 1, pages.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(id = R.string.ui_onboarding_skip))
            }
        },
        confirmButton = {
            val isLast = page >= pages.lastIndex
            TextButton(onClick = if (isLast) onFinish else onNext) {
                Text(
                    stringResource(
                        id = if (isLast) R.string.ui_onboarding_get_started else R.string.ui_onboarding_next,
                    ),
                )
            }
        },
    )
}

@Composable
private fun PrivacyInfoSheet(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(id = R.string.ui_privacy_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(id = R.string.ui_privacy_item_1), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(id = R.string.ui_privacy_item_2), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(id = R.string.ui_privacy_item_3), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.ui_close))
        }
    }
}

@Composable
private fun AdvancedSettingsSheet(
    state: ChatUiState,
    onRoutingModeSelected: (RoutingMode) -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(id = R.string.ui_advanced_controls_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(id = R.string.ui_model_selection_title), style = MaterialTheme.typography.labelLarge)

        RoutingMode.entries.forEach { mode ->
            val routingDescription = stringResource(id = R.string.a11y_routing_mode, mode.name)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.RadioButton
                        selected = state.runtime.routingMode == mode
                        contentDescription = routingDescription
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.runtime.routingMode == mode,
                    onClick = { onRoutingModeSelected(mode) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(mode.name)
            }
        }

        HorizontalDivider()

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onExportDiagnostics,
        ) {
            Text(stringResource(id = R.string.ui_export_diagnostics))
        }

        Text(
            text = stringResource(
                id = R.string.ui_model_status_label,
                when (state.runtime.modelRuntimeStatus) {
                    ModelRuntimeStatus.NOT_READY -> stringResource(id = R.string.ui_model_status_not_ready)
                    ModelRuntimeStatus.LOADING -> stringResource(id = R.string.ui_model_status_loading)
                    ModelRuntimeStatus.READY -> stringResource(id = R.string.ui_model_status_ready)
                    ModelRuntimeStatus.ERROR -> stringResource(id = R.string.ui_model_status_error)
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        state.runtime.modelStatusDetail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.runtime.activeModelId?.let { modelId ->
            Text(
                text = stringResource(id = R.string.ui_active_model_label, modelId),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.lastFirstTokenLatencyMs?.let { latency ->
            Text(
                text = stringResource(id = R.string.ui_first_token_latency_label, latency),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.runtime.lastTotalLatencyMs?.let { latency ->
            Text(
                text = stringResource(id = R.string.ui_total_latency_label, latency),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
