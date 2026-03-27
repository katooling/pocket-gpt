package com.pocketagent.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.R
import com.pocketagent.android.runtime.MODEL_OFFLOAD_REASON_MANUAL
import com.pocketagent.android.runtime.errorCodeName
import com.pocketagent.android.runtime.modelmanager.DownloadFailureReason
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.state.ChatGatePrimaryAction
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.android.ui.state.activeOrRequestedModel
import com.pocketagent.android.ui.state.resolveChatGateState
import com.pocketagent.core.RoutingMode
import com.pocketagent.runtime.ModelInteractionRegistry
import com.pocketagent.runtime.ThinkingSupport
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PocketAgentApp(
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val modelLoadingState by viewModel.modelLoadingState.collectAsState()
    val provisioningState by provisioningViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val downloads = provisioningState.downloads
    val provisioningSnapshot = provisioningState.snapshot ?: return
    val chatGateState = resolveChatGateState(
        runtime = state.runtime,
        provisioningSnapshot = provisioningSnapshot,
        advancedUnlocked = state.advancedUnlocked,
    )
    val activeRuntimeModelLabel = modelLoadingState.loadedModel?.let { model ->
        "${model.modelId} ${model.modelVersion.orEmpty()}".trim()
    }
    val drawerState = rememberDrawerState(
        initialValue = if (state.isSessionDrawerOpen) DrawerValue.Open else DrawerValue.Closed,
    )
    val interactionRegistry = remember { ModelInteractionRegistry() }
    val thinkingToggleModelId = modelLoadingState.loadedModel?.modelId ?: state.runtime.activeModelId
    val showThinkingToggle = thinkingToggleModelId?.let { modelId ->
        runCatching {
            interactionRegistry.interactionProfileForModel(modelId).thinkingSupport == ThinkingSupport.THINK_TAGS
        }.getOrDefault(false)
    } == true
    val canLoadLastUsedModel = modelLoadingState.loadedModel == null &&
        modelLoadingState.lastUsedModel != null &&
        modelLoadingState !is ModelLoadingState.Loading &&
        modelLoadingState !is ModelLoadingState.Offloading
    val lastUsedModelLabel = modelLoadingState.lastUsedModel?.let { model ->
        "${model.modelId} ${model.modelVersion.orEmpty()}".trim()
    }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var modelManagementSurface by remember { mutableStateOf(false) }
    var selectedModelIdForImport by remember { mutableStateOf<String?>(null) }
    var pendingGetReadyActivation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingMeteredWarningVersion by remember { mutableStateOf<ModelDistributionVersion?>(null) }
    var pendingNotificationPermissionVersion by remember { mutableStateOf<ModelDistributionVersion?>(null) }
    var pendingRoutingModeSwitch by remember { mutableStateOf<Pair<String, String>?>(null) }
    var lastDownloadTransitionRefreshKey by remember { mutableStateOf<String?>(null) }
    var readinessRefreshSequence by remember { mutableStateOf(0L) }
    val previousDownloadStatuses = remember { mutableStateMapOf<String, DownloadTaskStatus>() }
    val defaultGetReadyModelId = remember { resolveDefaultGetReadyModelId(isDebugBuild = BuildConfig.DEBUG) }
    val modelLibraryState = provisioningState.toModelLibraryUiState(defaultGetReadyModelId) ?: return
    val runtimeModelState = provisioningState.toRuntimeModelUiState() ?: return
    val beginDownload: (ModelDistributionVersion) -> Unit = { version ->
        startModelDownload(
            context = context,
            version = version,
            enqueueDownload = { selected -> provisioningViewModel.enqueueDownload(selected) },
            onStatus = { message -> provisioningViewModel.setStatusMessage(message) },
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val version = pendingNotificationPermissionVersion
        pendingNotificationPermissionVersion = null
        if (version == null) {
            return@rememberLauncherForActivityResult
        }
        if (!granted) {
            provisioningViewModel.setStatusMessage(
                context.getString(R.string.ui_model_download_notifications_disabled),
            )
        }
        beginDownload(version)
    }
    val launchDownloadFlow: (ModelDistributionVersion) -> Unit = { version ->
        when {
            provisioningViewModel.shouldWarnForMeteredLargeDownload(version) -> {
                pendingMeteredWarningVersion = version
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                pendingNotificationPermissionVersion = version
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            else -> beginDownload(version)
        }
    }
    val openModelSheet: () -> Unit = {
        provisioningViewModel.refreshSnapshot()
        scope.launch { provisioningViewModel.refreshManifest() }
        modelManagementSurface = true
    }
    val showBusyModelOperationFeedback: () -> Unit = {
        scope.launch {
            snackbarHostState.showSnackbar("Model operation already in progress")
        }
    }
    val loadModelVersionAction: (String, String, Boolean) -> Unit = { modelId, version, closeOnSuccess ->
        scope.launch {
            val result = viewModel.loadModel(modelId = modelId, version = version)
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelId,
                    fallbackVersion = version,
                ),
            )
            if (result.success && closeOnSuccess) {
                modelManagementSurface = false
            }
        }
    }
    val loadLastUsedModelAction: (Boolean) -> Unit = { closeOnSuccess ->
        scope.launch {
            val result = viewModel.loadLastUsedModel()
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelLoadingState.lastUsedModel?.modelId,
                    fallbackVersion = modelLoadingState.lastUsedModel?.modelVersion,
                ),
            )
            if (result.success && closeOnSuccess) {
                modelManagementSurface = false
            }
        }
    }
    val offloadModelAction: (Boolean) -> Unit = { closeOnSuccess ->
        scope.launch {
            val result = viewModel.offloadModel(reason = MODEL_OFFLOAD_REASON_MANUAL)
            if (result == null) {
                showBusyModelOperationFeedback()
                return@launch
            }
            provisioningViewModel.setStatusMessage(
                lifecycleStatusMessage(
                    context = context,
                    result = result,
                    fallbackModelId = modelLoadingState.activeOrRequestedModel()?.modelId,
                    fallbackVersion = modelLoadingState.activeOrRequestedModel()?.modelVersion,
                ),
            )
            if (result.success && closeOnSuccess) {
                modelManagementSurface = false
            }
        }
    }
    val refreshAction: () -> Unit = {
        viewModel.refreshRuntimeReadiness()
        provisioningViewModel.refreshSnapshot()
        scope.launch { provisioningViewModel.refreshManifest() }
        provisioningViewModel.setStatusMessage(
            context.getString(R.string.ui_model_refresh_runtime_feedback),
        )
    }
    val runGetReadyFlow: () -> Unit = {
        scope.launch {
            viewModel.onGetReadyTapped()
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_get_ready_started_status))
            provisioningViewModel.refreshManifest()
            val manifest = provisioningViewModel.uiState.value.manifest
            val defaultVersion = resolveDefaultGetReadyVersion(
                manifest = manifest,
                defaultModelId = defaultGetReadyModelId,
            )
            if (defaultVersion == null) {
                provisioningViewModel.setStatusMessage(
                    context.getString(R.string.ui_model_downloads_manifest_empty),
                )
                openModelSheet()
                return@launch
            }

            val existingVersion = provisioningViewModel.listInstalledVersionsAsync(
                modelId = defaultVersion.modelId,
            ).firstOrNull { it.version == defaultVersion.version }

            if (existingVersion != null) {
                provisioningViewModel.setActiveVersionAsync(
                    modelId = defaultVersion.modelId,
                    version = defaultVersion.version,
                )
                val loadResult = viewModel.loadModel(
                    modelId = defaultVersion.modelId,
                    version = defaultVersion.version,
                )
                loadResult?.let { result ->
                    provisioningViewModel.setStatusMessage(
                        lifecycleStatusMessage(
                            context = context,
                            result = result,
                            fallbackModelId = defaultVersion.modelId,
                            fallbackVersion = defaultVersion.version,
                        ),
                    )
                } ?: showBusyModelOperationFeedback()
                return@launch
            }

            pendingGetReadyActivation = defaultVersion.modelId to defaultVersion.version
            launchDownloadFlow(defaultVersion)
            openModelSheet()
        }
    }
    val onBlockedAction: (ChatGatePrimaryAction) -> Unit = { action ->
        when (action) {
            ChatGatePrimaryAction.GET_READY -> runGetReadyFlow()
            ChatGatePrimaryAction.OPEN_MODEL_SETUP -> openModelSheet()
            ChatGatePrimaryAction.REFRESH_RUNTIME_CHECKS -> refreshAction()
            ChatGatePrimaryAction.NONE -> Unit
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.addAttachedImage(it.toString()) }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val modelId = selectedModelIdForImport ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_import_cancelled))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_import_in_progress))
            provisioningViewModel.importModelFromUri(
                modelId = modelId,
                sourceUri = uri,
            ).onSuccess { result ->
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
                provisioningViewModel.setStatusMessage(statusMessage)
            }.onFailure { error ->
                provisioningViewModel.setStatusMessage(
                    context.getString(
                        R.string.ui_model_import_failure,
                        error.message ?: "Unknown import error",
                    ),
                )
            }
        }
    }

    LaunchedEffect(downloads) {
        provisioningViewModel.refreshSnapshot()
        val transitioned = downloads.firstOrNull { task ->
            val previous = previousDownloadStatuses[task.taskId]
            previous != null && previous != task.status
        }
        val transitionFeedback = transitioned?.provisioningFeedback(context)
        transitionFeedback?.let { feedback ->
            provisioningViewModel.setStatusMessage(feedback)
        }
        if (
            transitioned?.status == DownloadTaskStatus.COMPLETED ||
            transitioned?.status == DownloadTaskStatus.INSTALLED_INACTIVE
        ) {
            var refreshDetail = transitionFeedback
            var refreshKey = transitioned.taskId + ":" + transitioned.status.name
            val pendingActivation = pendingGetReadyActivation
            if (
                pendingActivation != null &&
                transitioned.modelId == pendingActivation.first &&
                transitioned.version == pendingActivation.second
            ) {
                val activated = provisioningViewModel.setActiveVersionAsync(
                    modelId = transitioned.modelId,
                    version = transitioned.version,
                )
                if (activated) {
                    val activationMessage = context.getString(
                        R.string.ui_model_version_activated,
                        transitioned.modelId,
                        transitioned.version,
                    )
                    provisioningViewModel.setStatusMessage(
                        activationMessage,
                    )
                    refreshDetail = activationMessage
                    refreshKey += ":activated"
                    val loadResult = viewModel.loadModel(
                        modelId = transitioned.modelId,
                        version = transitioned.version,
                    )
                    loadResult?.let { result ->
                        provisioningViewModel.setStatusMessage(
                            lifecycleStatusMessage(
                                context = context,
                                result = result,
                                fallbackModelId = transitioned.modelId,
                                fallbackVersion = transitioned.version,
                            ),
                        )
                    } ?: showBusyModelOperationFeedback()
                    logProvisioningTransition(
                        phase = "download_activation",
                        eventId = transitioned.taskId,
                        detail = "${transitioned.modelId}@${transitioned.version}",
                    )
                } else {
                    refreshKey += ":activation_skipped"
                }
                pendingGetReadyActivation = null
            }
            if (lastDownloadTransitionRefreshKey != refreshKey) {
                readinessRefreshSequence += 1L
                logProvisioningTransition(
                    phase = "readiness_refresh",
                    eventId = "refresh-${readinessRefreshSequence}",
                    detail = "source=download_transition;task=${transitioned.taskId};status=${transitioned.status.name}",
                )
                viewModel.refreshRuntimeReadiness(statusDetailOverride = refreshDetail)
                lastDownloadTransitionRefreshKey = refreshKey
            } else {
                logProvisioningTransition(
                    phase = "readiness_refresh_coalesced",
                    eventId = "refresh-${readinessRefreshSequence}",
                    detail = "task=${transitioned.taskId};status=${transitioned.status.name}",
                )
            }
        }
        if (transitioned?.status == DownloadTaskStatus.FAILED && pendingGetReadyActivation != null) {
            pendingGetReadyActivation = null
            openModelSheet()
        }
        previousDownloadStatuses.clear()
        downloads.forEach { task ->
            previousDownloadStatuses[task.taskId] = task.status
        }
    }

    LaunchedEffect(modelManagementSurface) {
        if (!modelManagementSurface) return@LaunchedEffect
        provisioningViewModel.refreshSnapshot()
        provisioningViewModel.refreshManifest()
    }

    LaunchedEffect(state.isSessionDrawerOpen) {
        if (state.isSessionDrawerOpen) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        modifier = Modifier.semantics {
            testTagsAsResourceId = true
        },
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
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
                            modifier = Modifier.testTag("advanced_sheet_button"),
                            onClick = { viewModel.setAdvancedSheetOpen(true) },
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(id = R.string.a11y_advanced_controls_open),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                ComposerBar(
                    text = state.composer.text,
                    isSending = state.composer.isSending,
                    isCancelling = state.composer.isCancelling,
                    chatGateState = chatGateState,
                    editingMessageId = state.composer.editingMessageId,
                    attachedImages = state.composer.attachedImages,
                    activeSessionId = state.activeSessionId,
                    onTextChanged = viewModel::onComposerChanged,
                    onSend = viewModel::sendMessage,
                    onCancelSend = viewModel::cancelActiveSend,
                    onSubmitEdit = viewModel::submitEdit,
                    onCancelEdit = viewModel::cancelEdit,
                    onAttachImage = {
                        imagePicker.launch("image/*")
                    },
                    onRemoveImage = viewModel::removeAttachedImage,
                    showThinkingToggle = showThinkingToggle,
                    thinkingEnabled = state.activeSession?.completionSettings?.showThinking == true,
                    onToggleThinking = viewModel::toggleSessionThinking,
                    onOpenCompletionSettings = { viewModel.setCompletionSettingsOpen(true) },
                    onBlockedAction = onBlockedAction,
                )
            },
        ) { innerPadding ->
            ChatScreenBody(
                state = state,
                modelLoadingState = modelLoadingState,
                onSuggestedPrompt = viewModel::prefillComposer,
                onGetReadyTapped = runGetReadyFlow,
                onOpenModelLibrary = openModelSheet,
                canLoadLastUsedModel = canLoadLastUsedModel,
                lastUsedModelLabel = lastUsedModelLabel,
                onLoadLastUsedModel = { loadLastUsedModelAction(false) },
                activeRuntimeModelLabel = activeRuntimeModelLabel,
                onOpenRuntimeControls = openModelSheet,
                onOpenAdvanced = { viewModel.setAdvancedSheetOpen(true) },
                onRefreshRuntimeChecks = refreshAction,
                onOpenToolDialog = { viewModel.setToolDialogOpen(true) },
                onEditMessage = viewModel::editMessage,
                onRegenerateMessage = viewModel::regenerateResponse,
                onCopiedToClipboard = {
                    scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
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

    if (state.isAdvancedSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.setAdvancedSheetOpen(false) },
            sheetState = sheetState,
        ) {
            AdvancedSettingsSheet(
                state = state,
                wifiOnlyDownloadsEnabled = provisioningState.downloadPreferences.wifiOnlyEnabled,
                onDefaultThinkingEnabledChanged = viewModel::setDefaultThinkingEnabled,
                onRoutingModeSelected = { mode ->
                    viewModel.setRoutingMode(mode)
                    val targetModelId = com.pocketagent.inference.ModelCatalog.modelIdForRoutingMode(mode)
                    val targetVersion = modelLibraryState.snapshot.models
                        .firstOrNull { model -> model.modelId == targetModelId }
                        ?.installedVersions
                        ?.firstOrNull { version -> version.isActive }
                        ?.version
                        ?: modelLibraryState.snapshot.models
                            .firstOrNull { model -> model.modelId == targetModelId }
                            ?.installedVersions
                            ?.firstOrNull()
                            ?.version
                    if (
                        mode != RoutingMode.AUTO &&
                        targetModelId != null &&
                        !targetVersion.isNullOrBlank() &&
                        modelLoadingState.loadedModel?.modelId != targetModelId
                    ) {
                        pendingRoutingModeSwitch = targetModelId to targetVersion
                    }
                },
                onPerformanceProfileSelected = viewModel::setPerformanceProfile,
                onKeepAlivePreferenceSelected = viewModel::setKeepAlivePreference,
                onWifiOnlyDownloadsChanged = provisioningViewModel::setDownloadWifiOnlyEnabled,
                onGpuAccelerationEnabledChanged = viewModel::setGpuAccelerationEnabled,
                onExportDiagnostics = viewModel::exportDiagnostics,
            )
        }
    }

    if (state.isCompletionSettingsOpen) {
        val completionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.setCompletionSettingsOpen(false) },
            sheetState = completionSheetState,
        ) {
            CompletionSettingsSheet(
                settings = state.activeSession?.completionSettings ?: com.pocketagent.android.ui.state.CompletionSettings(),
                onSettingsChanged = viewModel::updateSessionCompletionSettings,
                onClose = { viewModel.setCompletionSettingsOpen(false) },
            )
        }
    }

    if (modelManagementSurface) {
        val runtimeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { modelManagementSurface = false },
            sheetState = runtimeSheetState,
        ) {
            ModelSheet(
                libraryState = modelLibraryState,
                runtimeState = runtimeModelState,
                modelLoadingState = modelLoadingState,
                routingMode = state.runtime.routingMode,
                onImportModel = { modelId ->
                    selectedModelIdForImport = modelId
                    modelPicker.launch(arrayOf("*/*"))
                },
                onDownloadVersion = launchDownloadFlow,
                onPauseDownload = { taskId ->
                    provisioningViewModel.pauseDownload(taskId)
                    provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_paused))
                },
                onResumeDownload = { taskId ->
                    provisioningViewModel.resumeDownload(taskId)
                    provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_resumed))
                },
                onRetryDownload = { taskId ->
                    provisioningViewModel.retryDownload(taskId)
                    provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_retried))
                },
                onCancelDownload = { taskId ->
                    provisioningViewModel.cancelDownload(taskId)
                    provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_cancelled))
                },
                onSetDefaultVersion = { modelId, version ->
                    scope.launch {
                        val activated = provisioningViewModel.setActiveVersionAsync(modelId, version)
                        if (activated) {
                            readinessRefreshSequence += 1L
                            logProvisioningTransition(
                                phase = "manual_activation",
                                eventId = "refresh-${readinessRefreshSequence}",
                                detail = "$modelId@$version",
                            )
                            viewModel.refreshRuntimeReadiness(
                                statusDetailOverride = context.getString(
                                    R.string.ui_model_version_activated,
                                    modelId,
                                    version,
                                ),
                            )
                            provisioningViewModel.setStatusMessage(
                                context.getString(
                                    R.string.ui_model_version_activated,
                                    modelId,
                                    version,
                                ),
                            )
                        } else {
                            provisioningViewModel.setStatusMessage(
                                context.getString(R.string.ui_model_version_activation_failed),
                            )
                        }
                    }
                },
                onLoadVersion = { modelId, version -> loadModelVersionAction(modelId, version, true) },
                onLoadLastUsedModel = { loadLastUsedModelAction(true) },
                onOffloadModel = { offloadModelAction(false) },
                onRemoveVersion = { modelId, version ->
                    scope.launch {
                        val removed = provisioningViewModel.removeVersionAsync(modelId, version)
                        provisioningViewModel.setStatusMessage(
                            if (removed) {
                                context.getString(R.string.ui_model_version_removed, modelId, version)
                            } else {
                                context.getString(R.string.ui_model_version_remove_blocked)
                            },
                        )
                    }
                },
                onRefreshAll = {
                    scope.launch {
                        provisioningViewModel.refreshManifest()
                        provisioningViewModel.refreshDownloads()
                        provisioningViewModel.refreshSnapshot()
                        viewModel.refreshRuntimeReadiness()
                        provisioningViewModel.setStatusMessage(
                            context.getString(R.string.ui_model_refresh_runtime_feedback),
                        )
                    }
                },
                onClose = { modelManagementSurface = false },
            )
        }
    }

    pendingRoutingModeSwitch?.let { (modelId, version) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRoutingModeSwitch = null },
            title = { Text("Switch model now?") },
            text = { Text("Routing now prefers $modelId. Load $version into memory now?") },
            dismissButton = {
                TextButton(onClick = { pendingRoutingModeSwitch = null }) {
                    Text("Later")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRoutingModeSwitch = null
                        loadModelVersionAction(modelId, version, false)
                    },
                ) {
                    Text("Load")
                }
            },
        )
    }

    pendingMeteredWarningVersion?.let { version ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingMeteredWarningVersion = null },
            title = { Text(stringResource(id = R.string.ui_large_download_metered_title)) },
            text = {
                Text(
                    stringResource(
                        id = R.string.ui_large_download_metered_body,
                        version.modelId,
                        version.fileSizeBytes.formatAsGiB(),
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingMeteredWarningVersion = null }) {
                    Text(stringResource(id = R.string.ui_cancel_button))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        provisioningViewModel.acknowledgeLargeDownloadCellularWarning()
                        pendingMeteredWarningVersion = null
                        launchDownloadFlow(version)
                    },
                ) {
                    Text(stringResource(id = R.string.ui_large_download_metered_continue))
                }
            },
        )
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

private fun lifecycleStatusMessage(
    context: android.content.Context,
    result: RuntimeModelLifecycleCommandResult,
    fallbackModelId: String?,
    fallbackVersion: String?,
): String {
    if (result.success) {
        if (result.queued) {
            return context.getString(R.string.ui_model_runtime_offload_queued)
        }
        val loaded = result.loadedModel
        if (loaded != null) {
            return context.getString(
                R.string.ui_model_runtime_loaded_message,
                loaded.modelId,
                loaded.modelVersion.orEmpty(),
            )
        }
        return context.getString(R.string.ui_model_runtime_offloaded_message)
    }
    val modelId = fallbackModelId.orEmpty()
    val version = fallbackVersion.orEmpty()
    return when (result.errorCodeName()) {
        "MODEL_FILE_UNAVAILABLE" -> context.getString(
            R.string.ui_model_runtime_error_model_file_unavailable,
            modelId,
            version,
        )
        "RUNTIME_INCOMPATIBLE" -> context.getString(
            R.string.ui_model_runtime_error_incompatible,
            modelId,
            version,
        )
        "BACKEND_INIT_FAILED" -> context.getString(
            R.string.ui_model_runtime_error_backend_init,
            result.detail ?: "backend_init_failed",
        )
        "OUT_OF_MEMORY" -> context.getString(
            R.string.ui_model_runtime_error_out_of_memory,
            result.detail ?: "out_of_memory",
        )
        "BUSY_GENERATION" -> context.getString(R.string.ui_model_runtime_error_busy_generation)
        "CANCELLED_BY_NEWER_REQUEST" -> context.getString(
            R.string.ui_model_runtime_error_cancelled_newer_request,
        )
        null,
        "UNKNOWN" -> context.getString(
            R.string.ui_model_runtime_error_unknown,
            result.detail ?: "unknown",
        )
        else -> context.getString(
            R.string.ui_model_runtime_error_unknown,
            result.detail ?: result.errorCodeName()?.lowercase() ?: "unknown",
        )
    }
}

private fun startModelDownload(
    context: android.content.Context,
    version: ModelDistributionVersion,
    enqueueDownload: (ModelDistributionVersion) -> String,
    onStatus: (String) -> Unit,
) {
    runCatching { enqueueDownload(version) }
        .onSuccess { taskId ->
            onStatus(
                context.getString(
                    R.string.ui_model_download_enqueued,
                    version.modelId,
                    version.version,
                    taskId,
                ),
            )
        }
        .onFailure { error ->
            onStatus(
                context.getString(
                    R.string.ui_model_download_start_failed,
                    version.modelId,
                    version.version,
                    error.message ?: "unknown error",
                ),
            )
        }
}

private fun DownloadTaskState.provisioningFeedback(context: android.content.Context): String? {
    return when (status) {
        DownloadTaskStatus.COMPLETED -> context.getString(
            R.string.ui_model_download_verified_active,
            modelId,
            version,
        )
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

private fun logProvisioningTransition(
    phase: String,
    eventId: String,
    detail: String,
) {
    runCatching {
        Log.i("PocketAgentApp", "MODEL_TRANSITION|phase=$phase|event_id=$eventId|detail=$detail")
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
