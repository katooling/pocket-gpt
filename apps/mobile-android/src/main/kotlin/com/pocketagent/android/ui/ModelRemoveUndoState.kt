package com.pocketagent.android.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.pocketagent.android.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ModelRemoveUndoState(
    val hiddenVersionKeys: Set<String>,
    val requestRemove: (modelId: String, version: String) -> Unit,
)

@Composable
internal fun rememberModelRemoveUndoState(
    snackbarHostState: SnackbarHostState,
    onCommitRemove: (modelId: String, version: String) -> Unit,
): ModelRemoveUndoState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnCommitRemove by rememberUpdatedState(onCommitRemove)
    val hiddenVersionKeys = remember { mutableStateMapOf<String, Boolean>() }
    val pendingRemoveJobs = remember { mutableStateMapOf<String, Job>() }

    DisposableEffect(Unit) {
        onDispose {
            pendingRemoveJobs.values.forEach { it.cancel() }
        }
    }

    val requestRemove: (modelId: String, version: String) -> Unit = { modelId, version ->
        val key = "$modelId::$version"
        if (!hiddenVersionKeys.containsKey(key)) {
            hiddenVersionKeys[key] = true
            pendingRemoveJobs[key]?.cancel()
            pendingRemoveJobs[key] = scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(
                        R.string.ui_model_version_remove_snackbar,
                        modelId,
                        version,
                    ),
                    actionLabel = context.getString(R.string.ui_undo),
                    duration = SnackbarDuration.Long,
                )
                when (result) {
                    SnackbarResult.ActionPerformed -> hiddenVersionKeys.remove(key)
                    SnackbarResult.Dismissed -> {
                        currentOnCommitRemove(modelId, version)
                        hiddenVersionKeys.remove(key)
                    }
                }
                pendingRemoveJobs.remove(key)
            }
        }
    }

    return ModelRemoveUndoState(
        hiddenVersionKeys = hiddenVersionKeys.keys.toSet(),
        requestRemove = requestRemove,
    )
}
