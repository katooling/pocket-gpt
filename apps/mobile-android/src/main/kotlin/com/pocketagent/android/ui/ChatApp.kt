package com.pocketagent.android.ui

import android.net.Uri
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.AppRuntimeDependencies
import com.pocketagent.android.runtime.modelmanager.DownloadFailureReason
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.StartupProbeState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketAgentApp(
    viewModel: ChatViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val downloads by AppRuntimeDependencies.observeDownloads(context).collectAsState()
    val drawerState = rememberDrawerState(
        initialValue = if (state.isSessionDrawerOpen) DrawerValue.Open else DrawerValue.Closed,
    )
    val runtimeReadyForSend = state.runtime.startupProbeState == StartupProbeState.READY ||
        state.runtime.startupProbeState == StartupProbeState.DEGRADED
    val runtimeReadyForModelActions = state.runtime.startupProbeState == StartupProbeState.READY &&
        state.runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
    val scope = rememberCoroutineScope()
    var modelSheetOpen by remember { mutableStateOf(false) }
    var selectedModelIdForImport by remember { mutableStateOf<String?>(null) }
    var modelImportInProgress by remember { mutableStateOf(false) }
    var modelProvisioningStatus by remember { mutableStateOf<String?>(null) }
    val previousDownloadStatuses = remember { mutableStateMapOf<String, DownloadTaskStatus>() }
    var modelDistributionManifest by remember { mutableStateOf(ModelDistributionManifest(models = emptyList())) }
    var provisioningSnapshot by remember {
        mutableStateOf(AppRuntimeDependencies.currentProvisioningSnapshot(context))
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.attachImage(it.toString()) }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val modelId = selectedModelIdForImport ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            modelProvisioningStatus = context.getString(R.string.ui_model_import_cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            modelImportInProgress = true
            modelProvisioningStatus = context.getString(R.string.ui_model_import_in_progress)
            runCatching {
                AppRuntimeDependencies.importModelFromUri(
                    context = context,
                    modelId = modelId,
                    sourceUri = uri,
                )
            }.onSuccess { result ->
                provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
                val statusMessage = if (result.isActive) {
                    context.getString(
                        R.string.ui_model_import_success_active,
                        result.modelId,
                        result.version,
                    )
                } else {
                    context.getString(
                        R.string.ui_model_import_success_inactive,
                        result.modelId,
                        result.version,
                    )
                }
                viewModel.refreshRuntimeReadiness(
                    statusDetailOverride = statusMessage,
                )
                modelProvisioningStatus = statusMessage
            }.onFailure { error ->
                modelProvisioningStatus = context.getString(
                    R.string.ui_model_import_failure,
                    error.message ?: "Unknown import error",
                )
            }
            modelImportInProgress = false
        }
    }

    LaunchedEffect(downloads) {
        provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
        val transitioned = downloads.firstOrNull { task ->
            val previous = previousDownloadStatuses[task.taskId]
            previous != null && previous != task.status
        }
        transitioned
            ?.provisioningFeedback(context)
            ?.let { feedback ->
                modelProvisioningStatus = feedback
            }
        previousDownloadStatuses.clear()
        downloads.forEach { task ->
            previousDownloadStatuses[task.taskId] = task.status
        }
    }

    LaunchedEffect(modelSheetOpen) {
        if (!modelSheetOpen) {
            return@LaunchedEffect
        }
        provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
        modelDistributionManifest = AppRuntimeDependencies.loadModelDistributionManifest(context)
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
                    isSendAllowed = runtimeReadyForSend,
                    isModelActionsReady = runtimeReadyForModelActions,
                    onTextChanged = viewModel::onComposerChanged,
                    onSend = viewModel::sendMessage,
                    onCancelSend = viewModel::cancelActiveSend,
                    onAttachImage = { imagePicker.launch("image/*") },
                )
            },
        ) { innerPadding ->
            ChatScreenBody(
                state = state,
                onSuggestedPrompt = viewModel::prefillComposer,
                onOpenModelSetup = {
                    provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
                    modelSheetOpen = true
                },
                onRefreshRuntimeChecks = {
                    viewModel.refreshRuntimeReadiness()
                    provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
                    modelProvisioningStatus = context.getString(R.string.ui_model_refresh_runtime_feedback)
                },
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
                onOpenModelSetup = {
                    provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
                    modelSheetOpen = true
                },
            )
        }
    }

    if (modelSheetOpen) {
        val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { modelSheetOpen = false },
            sheetState = modelSheetState,
        ) {
            ModelProvisioningSheet(
                snapshot = provisioningSnapshot,
                manifest = modelDistributionManifest,
                downloads = downloads,
                isImporting = modelImportInProgress,
                statusMessage = modelProvisioningStatus,
                onImportModel = { modelId ->
                    selectedModelIdForImport = modelId
                    modelPicker.launch(arrayOf("*/*"))
                },
                onDownloadVersion = { version ->
                    startModelDownload(
                        context = context,
                        version = version,
                        onStatus = { modelProvisioningStatus = it },
                    )
                },
                onPauseDownload = { taskId ->
                    AppRuntimeDependencies.pauseDownload(context, taskId)
                    modelProvisioningStatus = context.getString(R.string.ui_model_download_paused)
                },
                onResumeDownload = { taskId ->
                    AppRuntimeDependencies.resumeDownload(context, taskId)
                    modelProvisioningStatus = context.getString(R.string.ui_model_download_resumed)
                },
                onRetryDownload = { taskId ->
                    AppRuntimeDependencies.retryDownload(context, taskId)
                    modelProvisioningStatus = context.getString(R.string.ui_model_download_retried)
                },
                onActivateVersion = { modelId, version ->
                    val activated = AppRuntimeDependencies.setActiveVersion(context, modelId, version)
                    provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
                    if (activated) {
                        viewModel.refreshRuntimeReadiness(
                            statusDetailOverride = context.getString(
                                R.string.ui_model_version_activated,
                                modelId,
                                version,
                            ),
                        )
                        modelProvisioningStatus = context.getString(
                            R.string.ui_model_version_activated,
                            modelId,
                            version,
                        )
                    } else {
                        modelProvisioningStatus = context.getString(
                            R.string.ui_model_version_activation_failed,
                        )
                    }
                },
                onRemoveVersion = { modelId, version ->
                    val removed = AppRuntimeDependencies.removeVersion(context, modelId, version)
                    provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
                    modelProvisioningStatus = if (removed) {
                        context.getString(R.string.ui_model_version_removed, modelId, version)
                    } else {
                        context.getString(R.string.ui_model_version_remove_blocked)
                    }
                },
                onRefreshManifest = {
                    scope.launch {
                        modelDistributionManifest = AppRuntimeDependencies.loadModelDistributionManifest(context)
                        modelProvisioningStatus = if (modelDistributionManifest.models.isEmpty()) {
                            context.getString(R.string.ui_model_downloads_manifest_empty)
                        } else {
                            context.getString(R.string.ui_model_refresh_manifest_success)
                        }
                    }
                },
                onRefreshRuntime = {
                    viewModel.refreshRuntimeReadiness()
                    provisioningSnapshot = AppRuntimeDependencies.currentProvisioningSnapshot(context)
                    modelProvisioningStatus = context.getString(R.string.ui_model_refresh_runtime_feedback)
                },
                onClose = { modelSheetOpen = false },
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

private fun startModelDownload(
    context: android.content.Context,
    version: ModelDistributionVersion,
    onStatus: (String) -> Unit,
) {
    val taskId = AppRuntimeDependencies.enqueueDownload(context, version)
    onStatus(
        context.getString(
            R.string.ui_model_download_enqueued,
            version.modelId,
            version.version,
            taskId,
        ),
    )
}

private fun DownloadTaskState.provisioningFeedback(context: android.content.Context): String? {
    return when (status) {
        DownloadTaskStatus.INSTALLED_INACTIVE -> context.getString(
            R.string.ui_model_download_verified_inactive,
            modelId,
            version,
        )
        DownloadTaskStatus.FAILED -> {
            when (failureReason) {
                DownloadFailureReason.CHECKSUM_MISMATCH -> context.getString(
                    R.string.ui_model_download_failed_checksum,
                    modelId,
                    version,
                )
                DownloadFailureReason.PROVENANCE_MISMATCH -> context.getString(
                    R.string.ui_model_download_failed_provenance,
                    modelId,
                    version,
                )
                DownloadFailureReason.RUNTIME_INCOMPATIBLE -> context.getString(
                    R.string.ui_model_download_failed_runtime_compat,
                    modelId,
                    version,
                )
                DownloadFailureReason.INSUFFICIENT_STORAGE -> context.getString(
                    R.string.ui_model_download_failed_storage,
                    modelId,
                    version,
                )
                DownloadFailureReason.NETWORK_UNAVAILABLE,
                DownloadFailureReason.NETWORK_ERROR,
                -> context.getString(
                    R.string.ui_model_download_failed_network,
                    modelId,
                    version,
                )
                DownloadFailureReason.TIMEOUT -> context.getString(
                    R.string.ui_model_download_failed_timeout,
                    modelId,
                    version,
                )
                DownloadFailureReason.CANCELLED -> context.getString(
                    R.string.ui_model_download_failed_cancelled,
                    modelId,
                    version,
                )
                DownloadFailureReason.UNKNOWN,
                null,
                -> context.getString(
                    R.string.ui_model_download_failed_unknown,
                    modelId,
                    version,
                )
            }
        }
        else -> null
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
