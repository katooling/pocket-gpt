package com.pocketagent.android.ui

import android.net.Uri
import android.util.Log
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
import com.pocketagent.android.BuildConfig
import com.pocketagent.android.R
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.android.runtime.modelmanager.DownloadFailureReason
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.state.ModelRuntimeStatus
import com.pocketagent.android.ui.state.StartupProbeState
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketAgentApp(
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val provisioningState by provisioningViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val downloads = provisioningState.downloads
    val provisioningSnapshot = provisioningState.snapshot ?: return
    val modelDistributionManifest = provisioningState.manifest
    val modelImportInProgress = provisioningState.isImporting
    val modelProvisioningStatus = provisioningState.statusMessage
    val drawerState = rememberDrawerState(
        initialValue = if (state.isSessionDrawerOpen) DrawerValue.Open else DrawerValue.Closed,
    )
    val runtimeReadyForSend = state.runtime.startupProbeState == StartupProbeState.READY &&
        state.runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
    val runtimeReadyForModelActions = state.runtime.startupProbeState == StartupProbeState.READY &&
        state.runtime.modelRuntimeStatus == ModelRuntimeStatus.READY
    val canLoadLastUsedModel = provisioningState.lifecycle.loadedModel == null &&
        provisioningState.lifecycle.lastUsedModel != null &&
        provisioningState.lifecycle.state != com.pocketagent.nativebridge.ModelLifecycleState.LOADING
    val lastUsedModelLabel = provisioningState.lifecycle.lastUsedModel?.let { model ->
        "${model.modelId} ${model.modelVersion.orEmpty()}".trim()
    }
    val scope = rememberCoroutineScope()
    var modelSheetOpen by remember { mutableStateOf(false) }
    var selectedModelIdForImport by remember { mutableStateOf<String?>(null) }
    var pendingGetReadyActivation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var lastDownloadTransitionRefreshKey by remember { mutableStateOf<String?>(null) }
    var readinessRefreshSequence by remember { mutableStateOf(0L) }
    val previousDownloadStatuses = remember { mutableStateMapOf<String, DownloadTaskStatus>() }
    val defaultGetReadyModelId = remember { resolveDefaultGetReadyModelId(isDebugBuild = BuildConfig.DEBUG) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.attachImage(it.toString()) }
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
            modelSheetOpen = true
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
                onGetReadyTapped = {
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
                            modelSheetOpen = true
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
                            val activatedMessage = context.getString(
                                R.string.ui_model_version_activated,
                                defaultVersion.modelId,
                                defaultVersion.version,
                            )
                            provisioningViewModel.setStatusMessage(activatedMessage)
                            viewModel.refreshRuntimeReadiness(statusDetailOverride = activatedMessage)
                            return@launch
                        }

                        pendingGetReadyActivation = defaultVersion.modelId to defaultVersion.version
                        startModelDownload(
                            context = context,
                            version = defaultVersion,
                            enqueueDownload = provisioningViewModel::enqueueDownload,
                            onStatus = { message -> provisioningViewModel.setStatusMessage(message) },
                        )
                        modelSheetOpen = true
                    }
                },
                onOpenModelSetup = {
                    provisioningViewModel.refreshSnapshot()
                    modelSheetOpen = true
                },
                canLoadLastUsedModel = canLoadLastUsedModel,
                lastUsedModelLabel = lastUsedModelLabel,
                onLoadLastUsedModel = {
                    scope.launch {
                        val result = provisioningViewModel.loadLastUsedModel()
                        provisioningViewModel.setStatusMessage(
                            lifecycleStatusMessage(
                                context = context,
                                result = result,
                                fallbackModelId = provisioningState.lifecycle.lastUsedModel?.modelId,
                                fallbackVersion = provisioningState.lifecycle.lastUsedModel?.modelVersion,
                            ),
                        )
                    }
                },
                onOpenAdvanced = { viewModel.setAdvancedSheetOpen(true) },
                onRefreshRuntimeChecks = {
                    viewModel.refreshRuntimeReadiness()
                    provisioningViewModel.refreshSnapshot()
                    provisioningViewModel.setStatusMessage(
                        context.getString(R.string.ui_model_refresh_runtime_feedback),
                    )
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
                onPerformanceProfileSelected = viewModel::setPerformanceProfile,
                onKeepAlivePreferenceSelected = viewModel::setKeepAlivePreference,
                onGpuAccelerationEnabledChanged = viewModel::setGpuAccelerationEnabled,
                onExportDiagnostics = viewModel::exportDiagnostics,
                onOpenModelSetup = {
                    provisioningViewModel.refreshSnapshot()
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
                lifecycle = provisioningState.lifecycle,
                manifest = modelDistributionManifest,
                downloads = downloads,
                isImporting = modelImportInProgress,
                statusMessage = modelProvisioningStatus,
                defaultGetReadyModelId = defaultGetReadyModelId,
                onImportModel = { modelId ->
                    selectedModelIdForImport = modelId
                    modelPicker.launch(arrayOf("*/*"))
                },
                onDownloadVersion = { version ->
                    startModelDownload(
                        context = context,
                        version = version,
                        enqueueDownload = provisioningViewModel::enqueueDownload,
                        onStatus = { message -> provisioningViewModel.setStatusMessage(message) },
                    )
                },
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
                onActivateVersion = { modelId, version ->
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
                                context.getString(
                                    R.string.ui_model_version_activation_failed,
                                ),
                            )
                        }
                    }
                },
                onLoadVersion = { modelId, version ->
                    scope.launch {
                        val result = provisioningViewModel.loadInstalledModel(modelId = modelId, version = version)
                        provisioningViewModel.setStatusMessage(
                            lifecycleStatusMessage(
                                context = context,
                                result = result,
                                fallbackModelId = modelId,
                                fallbackVersion = version,
                            ),
                        )
                    }
                },
                onLoadLastUsedModel = {
                    scope.launch {
                        val result = provisioningViewModel.loadLastUsedModel()
                        provisioningViewModel.setStatusMessage(
                            lifecycleStatusMessage(
                                context = context,
                                result = result,
                                fallbackModelId = provisioningState.lifecycle.lastUsedModel?.modelId,
                                fallbackVersion = provisioningState.lifecycle.lastUsedModel?.modelVersion,
                            ),
                        )
                    }
                },
                onOffloadModel = { reason ->
                    scope.launch {
                        val result = provisioningViewModel.offloadModel(reason = reason)
                        provisioningViewModel.setStatusMessage(
                            lifecycleStatusMessage(
                                context = context,
                                result = result,
                                fallbackModelId = provisioningState.lifecycle.loadedModel?.modelId,
                                fallbackVersion = provisioningState.lifecycle.loadedModel?.modelVersion,
                            ),
                        )
                    }
                },
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
                onRefreshManifest = {
                    scope.launch {
                        provisioningViewModel.refreshManifest()
                        val manifest = provisioningViewModel.uiState.value.manifest
                        provisioningViewModel.setStatusMessage(
                            if (manifest.models.isEmpty()) {
                                context.getString(R.string.ui_model_downloads_manifest_empty)
                            } else {
                                context.getString(R.string.ui_model_refresh_manifest_success)
                            },
                        )
                    }
                },
                onRefreshRuntime = {
                    viewModel.refreshRuntimeReadiness()
                    provisioningViewModel.refreshSnapshot()
                    provisioningViewModel.setStatusMessage(
                        context.getString(R.string.ui_model_refresh_runtime_feedback),
                    )
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
    return when (result.errorCode) {
        ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE -> context.getString(
            R.string.ui_model_runtime_error_model_file_unavailable,
            modelId,
            version,
        )
        ModelLifecycleErrorCode.RUNTIME_INCOMPATIBLE -> context.getString(
            R.string.ui_model_runtime_error_incompatible,
            modelId,
            version,
        )
        ModelLifecycleErrorCode.BACKEND_INIT_FAILED -> context.getString(
            R.string.ui_model_runtime_error_backend_init,
            result.detail ?: "backend_init_failed",
        )
        ModelLifecycleErrorCode.BUSY_GENERATION -> context.getString(R.string.ui_model_runtime_error_busy_generation)
        ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST -> context.getString(
            R.string.ui_model_runtime_error_cancelled_newer_request,
        )
        ModelLifecycleErrorCode.UNKNOWN,
        null,
        -> context.getString(
            R.string.ui_model_runtime_error_unknown,
            result.detail ?: "unknown",
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
