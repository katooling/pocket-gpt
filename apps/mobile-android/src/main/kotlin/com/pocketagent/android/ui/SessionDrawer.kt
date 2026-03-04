package com.pocketagent.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatUiState

@Composable
internal fun SessionDrawer(
    state: ChatUiState,
    onCreateSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val createSessionDescription = stringResource(id = R.string.a11y_create_session)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = R.string.ui_sessions_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onCreateSession) {
            Icon(Icons.Default.Add, contentDescription = createSessionDescription)
        }
    }
    HorizontalDivider()
    if (state.sessions.isEmpty()) {
        Text(
            text = stringResource(id = R.string.ui_no_sessions_yet),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.sessions.forEach { session ->
        val isActive = session.id == state.activeSessionId
        val activeStateDescription = if (isActive) {
            stringResource(id = R.string.a11y_session_active)
        } else {
            stringResource(id = R.string.a11y_session_inactive)
        }
        val switchSessionDescription = stringResource(
            id = R.string.a11y_switch_session,
            session.title,
        )
        val deleteSessionDescription = stringResource(
            id = R.string.a11y_delete_session,
            session.title,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .semantics {
                    selected = isActive
                    stateDescription = activeStateDescription
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                modifier = Modifier.semantics {
                    contentDescription = switchSessionDescription
                },
                onClick = { onSwitchSession(session.id) },
            ) {
                Text(
                    text = session.title,
                    maxLines = 1,
                    color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                modifier = Modifier.semantics {
                    contentDescription = deleteSessionDescription
                },
                onClick = { onDeleteSession(session.id) },
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
            }
        }
    }
}
