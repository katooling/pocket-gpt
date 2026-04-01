package com.pocketagent.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult

@Composable
internal fun DownloadTransitionHandler(
    downloads: List<DownloadTaskState>,
    pendingGetReadyActivation: Pair<String, String>?,
    loadedModelId: String?,
    lastDownloadTransitionRefreshKey: String?,
    readinessRefreshSequence: Long,
    onRefreshSnapshot: () -> Unit,
    onSetStatusMessage: (String) -> Unit,
    onActivateVersion: suspend (String, String) -> Boolean,
    onLoadModel: suspend (String, String) -> RuntimeModelLifecycleCommandResult?,
    onShowBusyModelOperationFeedback: suspend () -> Unit,
    onClearPendingGetReadyActivation: () -> Unit,
    onIncrementReadinessRefreshSequence: () -> Long,
    onRefreshRuntimeReadiness: (String?) -> Unit,
    onSetLastDownloadTransitionRefreshKey: (String) -> Unit,
    onOpenModelSheet: () -> Unit,
) {
    val context = LocalContext.current
    val previousDownloadStatuses = remember { mutableStateMapOf<String, DownloadTaskStatus>() }
    val currentPendingGetReadyActivation by rememberUpdatedState(pendingGetReadyActivation)
    val currentLoadedModelId by rememberUpdatedState(loadedModelId)
    val currentLastDownloadTransitionRefreshKey by rememberUpdatedState(lastDownloadTransitionRefreshKey)
    val currentReadinessRefreshSequence by rememberUpdatedState(readinessRefreshSequence)

    LaunchedEffect(downloads) {
        onRefreshSnapshot()
        val transitioned = downloads.firstOrNull { task ->
            val previous = previousDownloadStatuses[task.taskId]
            previous != null && previous != task.status
        }
        val transitionFeedback = transitioned?.provisioningFeedback(context)
        transitionFeedback?.let(onSetStatusMessage)
        if (
            transitioned?.status == DownloadTaskStatus.COMPLETED ||
            transitioned?.status == DownloadTaskStatus.INSTALLED_INACTIVE
        ) {
            var refreshDetail = transitionFeedback
            var refreshKey = transitioned.taskId + ":" + transitioned.status.name
            val pendingActivation = currentPendingGetReadyActivation
            if (
                pendingActivation != null &&
                transitioned.modelId == pendingActivation.first &&
                transitioned.version == pendingActivation.second
            ) {
                val activated = onActivateVersion(
                    transitioned.modelId,
                    transitioned.version,
                )
                if (activated) {
                    val activationMessage = context.getString(
                        com.pocketagent.android.R.string.ui_model_version_activated,
                        transitioned.modelId,
                        transitioned.version,
                    )
                    onSetStatusMessage(activationMessage)
                    refreshDetail = activationMessage
                    refreshKey += ":activated"
                    val alreadyLoadedDifferentModel =
                        currentLoadedModelId != null && currentLoadedModelId != transitioned.modelId
                    if (!alreadyLoadedDifferentModel) {
                        val loadResult = onLoadModel(transitioned.modelId, transitioned.version)
                        loadResult?.let { result ->
                            onSetStatusMessage(
                                lifecycleStatusMessage(
                                    context = context,
                                    result = result,
                                    fallbackModelId = transitioned.modelId,
                                    fallbackVersion = transitioned.version,
                                ),
                            )
                        } ?: onShowBusyModelOperationFeedback()
                    }
                    logProvisioningTransition(
                        phase = "download_activation",
                        eventId = transitioned.taskId,
                        detail = "${transitioned.modelId}@${transitioned.version}",
                    )
                } else {
                    refreshKey += ":activation_skipped"
                }
                onClearPendingGetReadyActivation()
            }
            val currentRefreshKey = currentLastDownloadTransitionRefreshKey
            if (currentRefreshKey != refreshKey) {
                val nextSequence = onIncrementReadinessRefreshSequence()
                logProvisioningTransition(
                    phase = "readiness_refresh",
                    eventId = "refresh-$nextSequence",
                    detail = "source=download_transition;task=${transitioned.taskId};status=${transitioned.status.name}",
                )
                onRefreshRuntimeReadiness(refreshDetail)
                onSetLastDownloadTransitionRefreshKey(refreshKey)
            } else {
                logProvisioningTransition(
                    phase = "readiness_refresh_coalesced",
                    eventId = "refresh-$currentReadinessRefreshSequence",
                    detail = "task=${transitioned.taskId};status=${transitioned.status.name}",
                )
            }
        }
        if (transitioned?.status == DownloadTaskStatus.FAILED && currentPendingGetReadyActivation != null) {
            onClearPendingGetReadyActivation()
            onOpenModelSheet()
        }
        previousDownloadStatuses.clear()
        downloads.forEach { task ->
            previousDownloadStatuses[task.taskId] = task.status
        }
    }
}
