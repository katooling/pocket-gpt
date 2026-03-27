package com.pocketagent.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.pocketagent.android.R
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.ui.state.ModelLoadingState
import com.pocketagent.core.RoutingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelManagementScreen(
    surface: ModelManagementSurface,
    libraryState: ModelLibraryUiState,
    runtimeState: RuntimeModelUiState,
    modelLoadingState: ModelLoadingState,
    routingMode: RoutingMode,
    onSurfaceChange: (ModelManagementSurface) -> Unit,
    onImportModel: (String) -> Unit,
    onDownloadVersion: (ModelDistributionVersion) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onSetDefaultVersion: (String, String) -> Unit,
    onLoadVersion: (String, String) -> Unit,
    onLoadLastUsedModel: () -> Unit,
    onOffloadModel: () -> Unit,
    onRemoveVersion: (String, String) -> Unit,
    onRefreshAll: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onClose)

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("model_management_screen"),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(id = R.string.ui_models_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(id = R.string.ui_close),
                            )
                        }
                    },
                )
                TabRow(selectedTabIndex = surface.ordinal) {
                    Tab(
                        selected = surface == ModelManagementSurface.LIBRARY,
                        onClick = { onSurfaceChange(ModelManagementSurface.LIBRARY) },
                        text = { Text(stringResource(id = R.string.ui_models_library_tab)) },
                        modifier = Modifier.testTag("model_management_library_tab"),
                    )
                    Tab(
                        selected = surface == ModelManagementSurface.RUNTIME,
                        onClick = { onSurfaceChange(ModelManagementSurface.RUNTIME) },
                        text = { Text(stringResource(id = R.string.ui_models_runtime_tab)) },
                        modifier = Modifier.testTag("model_management_runtime_tab"),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (surface) {
                ModelManagementSurface.LIBRARY -> ModelLibrarySheet(
                    state = libraryState,
                    modelLoadingState = modelLoadingState,
                    onImportModel = onImportModel,
                    onDownloadVersion = onDownloadVersion,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onRetryDownload = onRetryDownload,
                    onCancelDownload = onCancelDownload,
                    onActivateVersion = onSetDefaultVersion,
                    onRemoveVersion = onRemoveVersion,
                    onRefreshManifest = onRefreshAll,
                    onRefreshRuntime = onRefreshAll,
                    onRefreshAll = onRefreshAll,
                    onOpenRuntimeControls = { onSurfaceChange(ModelManagementSurface.RUNTIME) },
                    onClose = onClose,
                )

                ModelManagementSurface.RUNTIME -> RuntimeModelSheet(
                    state = runtimeState,
                    modelLoadingState = modelLoadingState,
                    routingMode = routingMode,
                    onLoadVersion = onLoadVersion,
                    onLoadLastUsedModel = onLoadLastUsedModel,
                    onOffloadModel = onOffloadModel,
                    onRefreshRuntime = onRefreshAll,
                    onOpenModelLibrary = { onSurfaceChange(ModelManagementSurface.LIBRARY) },
                    onClose = onClose,
                )
            }
        }
    }
}
