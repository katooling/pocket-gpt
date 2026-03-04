package com.pocketagent.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
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
    androidx.compose.material3.AlertDialog(
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
