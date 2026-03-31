package com.pocketagent.android.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pocketagent.android.R
import com.pocketagent.android.runtime.MODEL_OFFLOAD_REASON_MANUAL
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.components.AppBottomSheet
import com.pocketagent.android.ui.state.ModalSurface
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.core.RoutingMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelLibrarySheetHost(
    activeSurface: ModalSurface,
    modelLibraryState: ModelLibraryUiState,
    runtimeModelState: RuntimeModelUiState,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    viewModel: ChatViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
    appViewModel: ChatAppViewModel,
    onLaunchImportPicker: (String) -> Unit,
    onLaunchDownloadFlow: (ModelDistributionVersion) -> Unit,
    onLoadModelVersion: (String, String, Boolean) -> Unit,
    onLoadLastUsedModel: (Boolean) -> Unit,
    onOffloadModel: (Boolean) -> Unit,
) {
    if (activeSurface !is ModalSurface.ModelLibrary) {
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AppBottomSheet(
        title = stringResource(id = R.string.ui_model_library_title),
        sheetState = runtimeSheetState,
        onDismiss = viewModel::dismissSurface,
    ) {
        ModelSheet(
            libraryState = modelLibraryState,
            runtimeState = runtimeModelState,
            modelLoadingState = modelLoadingState,
            routingMode = routingMode,
            onEvent = { event ->
                when (event) {
                    is ModelSheetEvent.ImportModel -> {
                        appViewModel.setSelectedModelIdForImport(event.modelId)
                        onLaunchImportPicker(event.modelId)
                    }
                    is ModelSheetEvent.DownloadVersion -> onLaunchDownloadFlow(event.version)
                    is ModelSheetEvent.PauseDownload -> {
                        provisioningViewModel.pauseDownload(event.taskId)
                        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_paused))
                    }
                    is ModelSheetEvent.ResumeDownload -> {
                        provisioningViewModel.resumeDownload(event.taskId)
                        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_resumed))
                    }
                    is ModelSheetEvent.RetryDownload -> {
                        provisioningViewModel.retryDownload(event.taskId)
                        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_retried))
                    }
                    is ModelSheetEvent.CancelDownload -> {
                        provisioningViewModel.cancelDownload(event.taskId)
                        provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_download_cancelled))
                    }
                    is ModelSheetEvent.SetDefaultVersion -> {
                        scope.launch {
                            val activated = provisioningViewModel.setActiveVersionAsync(event.modelId, event.version)
                            if (activated) {
                                val nextSequence = appViewModel.incrementReadinessRefreshSequence()
                                logProvisioningTransition(
                                    phase = "manual_activation",
                                    eventId = "refresh-$nextSequence",
                                    detail = "${event.modelId}@${event.version}",
                                )
                                val statusMessage = context.getString(
                                    R.string.ui_model_version_activated,
                                    event.modelId,
                                    event.version,
                                )
                                viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
                                provisioningViewModel.setStatusMessage(statusMessage)
                            } else {
                                provisioningViewModel.setStatusMessage(
                                    context.getString(R.string.ui_model_version_activation_failed),
                                )
                            }
                        }
                    }
                    is ModelSheetEvent.LoadVersion -> onLoadModelVersion(event.modelId, event.version, true)
                    is ModelSheetEvent.RetryLoad -> {
                        if (event.version.isNullOrBlank()) {
                            onLoadLastUsedModel(true)
                        } else {
                            onLoadModelVersion(event.modelId, event.version, true)
                        }
                    }
                    ModelSheetEvent.LoadLastUsedModel -> onLoadLastUsedModel(true)
                    ModelSheetEvent.OffloadModel -> onOffloadModel(false)
                    is ModelSheetEvent.RemoveVersion -> {
                        scope.launch {
                            val model = modelLibraryState.snapshot.models
                                .firstOrNull { installedModel -> installedModel.modelId == event.modelId }
                            val version = model?.installedVersions
                                ?.firstOrNull { installedVersion -> installedVersion.version == event.version }
                            val removePlan = if (model != null && version != null) {
                                resolveRemoveVersionPlan(
                                    model = model,
                                    version = version,
                                    loadedModel = modelLoadingState.loadedModel,
                                )
                            } else {
                                null
                            }
                            if (removePlan?.isBlockedByActiveSelection == true) {
                                provisioningViewModel.setStatusMessage(
                                    context.getString(R.string.ui_model_version_remove_blocked),
                                )
                                return@launch
                            }
                            provisioningViewModel.setStatusMessage(
                                context.getString(
                                    R.string.ui_model_version_removing,
                                    event.modelId,
                                    event.version,
                                ),
                            )
                            if (removePlan?.requiresOffload == true) {
                                val offloadResult = provisioningViewModel.offloadModel(
                                    reason = MODEL_OFFLOAD_REASON_MANUAL,
                                )
                                if (!offloadResult.success || offloadResult.queued) {
                                    provisioningViewModel.setStatusMessage(
                                        lifecycleStatusMessage(
                                            context = context,
                                            result = offloadResult,
                                            fallbackModelId = event.modelId,
                                            fallbackVersion = event.version,
                                        ),
                                    )
                                    return@launch
                                }
                            }
                            if (removePlan?.requiresClearingActiveSelection == true) {
                                val cleared = provisioningViewModel.clearActiveVersionAsync(event.modelId)
                                if (!cleared) {
                                    provisioningViewModel.setStatusMessage(
                                        context.getString(R.string.ui_model_version_remove_failed),
                                    )
                                    return@launch
                                }
                            }
                            val removed = provisioningViewModel.removeVersionAsync(event.modelId, event.version)
                            val statusMessage = if (removed) {
                                context.getString(
                                    R.string.ui_model_version_removed,
                                    event.modelId,
                                    event.version,
                                )
                            } else {
                                context.getString(R.string.ui_model_version_remove_failed)
                            }
                            if (removed) {
                                viewModel.refreshRuntimeReadiness(statusDetailOverride = statusMessage)
                            }
                            provisioningViewModel.setStatusMessage(statusMessage)
                        }
                    }
                    ModelSheetEvent.RefreshAll -> {
                        scope.launch {
                            provisioningViewModel.refreshManifest()
                            provisioningViewModel.refreshDownloads()
                            provisioningViewModel.refreshSnapshot()
                            viewModel.refreshRuntimeReadiness()
                            provisioningViewModel.setStatusMessage(
                                context.getString(R.string.ui_model_refresh_runtime_feedback),
                            )
                        }
                    }
                    ModelSheetEvent.Close -> viewModel.dismissSurface()
                }
            },
        )
    }
}
