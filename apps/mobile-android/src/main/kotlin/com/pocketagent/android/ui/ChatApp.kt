package com.pocketagent.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketagent.android.RoutingMode
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import com.pocketagent.android.ui.state.MessageRole
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
                    title = { Text("Pocket GPT") },
                    navigationIcon = {
                        IconButton(
                            modifier = Modifier.testTag("session_drawer_button"),
                            onClick = {
                                viewModel.setSessionDrawerOpen(true)
                                scope.launch { drawerState.open() }
                            },
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Sessions")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setToolDialogOpen(true) }) {
                            Icon(Icons.Default.Build, contentDescription = "Tools")
                        }
                        IconButton(
                            modifier = Modifier.testTag("advanced_sheet_button"),
                            onClick = { viewModel.setAdvancedSheetOpen(true) },
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Advanced")
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (state.isToolDialogOpen) {
        ToolDialog(
            onDismiss = { viewModel.setToolDialogOpen(false) },
            onRunTool = { toolName, args ->
                viewModel.runTool(toolName = toolName, jsonArgs = args)
                viewModel.setToolDialogOpen(false)
            },
        )
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
}

@Composable
private fun SessionDrawer(
    state: ChatUiState,
    onCreateSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sessions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onCreateSession) {
            Icon(Icons.Default.Add, contentDescription = "Create session")
        }
    }
    Divider()
    if (state.sessions.isEmpty()) {
        Text(
            text = "No sessions yet",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.sessions.forEach { session ->
        val isActive = session.id == state.activeSessionId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onSwitchSession(session.id) }) {
                Text(
                    text = session.title,
                    maxLines = 1,
                    color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { onDeleteSession(session.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete session")
            }
        }
    }
}

@Composable
private fun ChatScreenBody(
    state: ChatUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp)) {
        OfflineAndStatusHeader(state = state)
        Spacer(modifier = Modifier.height(8.dp))
        MessageList(
            activeSession = state.activeSession,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("chat_message_list"),
        )
    }
}

@Composable
private fun OfflineAndStatusHeader(state: ChatUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            modifier = Modifier.testTag("offline_indicator"),
            onClick = { },
            label = { Text("Offline-first") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )
        AssistChip(
            onClick = { },
            label = { Text("Model: ${state.runtime.routingMode.name}") },
        )
    }

    if (state.runtime.startupChecks.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Startup checks",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                state.runtime.startupChecks.forEach { check ->
                    Text(
                        text = "- $check",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    state.runtime.lastError?.let { error ->
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
    modifier: Modifier = Modifier,
) {
    if (activeSession == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No session selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (activeSession.messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Start chatting. Ask a question, attach an image, or run a local tool.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(activeSession.messages, key = { it.id }) { message ->
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
                                    text = "Image: ${message.imagePath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            message.toolName != null -> {
                                Text(
                                    text = "Tool: ${message.toolName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        Text(
                            text = if (message.content.isBlank() && message.isStreaming) "…" else message.content,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onAttachImage) {
            Icon(Icons.Default.Image, contentDescription = "Attach image")
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier
                .weight(1f)
                .testTag("composer_input"),
            label = { Text("Message") },
            enabled = !isSending,
            maxLines = 4,
        )
        IconButton(
            modifier = Modifier.testTag("send_button"),
            onClick = onSend,
            enabled = text.isNotBlank() && !isSending,
        ) {
            Icon(Icons.Default.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun ToolDialog(
    onDismiss: () -> Unit,
    onRunTool: (toolName: String, args: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local tools") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onRunTool("calculator", "{\"expression\":\"4*9\"}") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Calculator (4*9)")
                }
                Button(
                    onClick = { onRunTool("date_time", "{}") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Date and Time")
                }
                Button(
                    onClick = { onRunTool("local_search", "{\"query\":\"launch checklist\"}") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Local Search")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
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
        Text("Advanced controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Model selection", style = MaterialTheme.typography.labelLarge)

        RoutingMode.entries.forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth(),
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

        Divider()

        Button(onClick = onExportDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Export diagnostics")
        }

        state.runtime.activeModelId?.let { modelId ->
            Text("Active model: $modelId", style = MaterialTheme.typography.bodySmall)
        }
        state.runtime.lastFirstTokenLatencyMs?.let { latency ->
            Text("Last first-token latency: ${latency}ms", style = MaterialTheme.typography.bodySmall)
        }
        state.runtime.lastTotalLatencyMs?.let { latency ->
            Text("Last total latency: ${latency}ms", style = MaterialTheme.typography.bodySmall)
        }
    }
}
