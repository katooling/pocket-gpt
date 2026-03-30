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
import com.pocketagent.android.ui.state.ChatSessionUiModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class SessionDeleteUndoState(
    val hiddenSessionIds: Set<String>,
    val requestDelete: (ChatSessionUiModel) -> Unit,
)

@Composable
internal fun rememberSessionDeleteUndoState(
    snackbarHostState: SnackbarHostState,
    onCommitDelete: (String) -> Unit,
): SessionDeleteUndoState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnCommitDelete by rememberUpdatedState(onCommitDelete)
    val hiddenSessionIds = remember { mutableStateMapOf<String, Boolean>() }
    val pendingDeleteJobs = remember { mutableStateMapOf<String, Job>() }

    DisposableEffect(Unit) {
        onDispose {
            pendingDeleteJobs.values.forEach { it.cancel() }
        }
    }

    val requestDelete: (ChatSessionUiModel) -> Unit = { session ->
        if (!hiddenSessionIds.containsKey(session.id)) {
            hiddenSessionIds[session.id] = true
            pendingDeleteJobs[session.id]?.cancel()
            pendingDeleteJobs[session.id] = scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.ui_session_delete_snackbar, session.title),
                    actionLabel = context.getString(R.string.ui_undo),
                    duration = SnackbarDuration.Long,
                )
                when (result) {
                    SnackbarResult.ActionPerformed -> hiddenSessionIds.remove(session.id)
                    SnackbarResult.Dismissed -> {
                        currentOnCommitDelete(session.id)
                        hiddenSessionIds.remove(session.id)
                    }
                }
                pendingDeleteJobs.remove(session.id)
            }
        }
    }

    return SessionDeleteUndoState(
        hiddenSessionIds = hiddenSessionIds.keys.toSet(),
        requestDelete = requestDelete,
    )
}
