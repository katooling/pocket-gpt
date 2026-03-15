package com.pocketagent.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.android.R
import com.pocketagent.android.ui.state.ChatSessionUiModel
import com.pocketagent.android.ui.state.ChatUiState
import java.util.Calendar
import java.util.concurrent.TimeUnit

private enum class SessionDateGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    LAST_WEEK("Last Week"),
    THIS_MONTH("This Month"),
    OLDER("Older"),
}

private fun classifyDateGroup(timestampMs: Long, nowMs: Long): SessionDateGroup {
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val todayStart = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterdayStart = todayStart - TimeUnit.DAYS.toMillis(1)
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
    val thisWeekStart = todayStart - TimeUnit.DAYS.toMillis(daysFromMonday.toLong())
    val lastWeekStart = thisWeekStart - TimeUnit.DAYS.toMillis(7)
    val thisMonthStart = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    return when {
        timestampMs >= todayStart -> SessionDateGroup.TODAY
        timestampMs >= yesterdayStart -> SessionDateGroup.YESTERDAY
        timestampMs >= thisWeekStart -> SessionDateGroup.THIS_WEEK
        timestampMs >= lastWeekStart -> SessionDateGroup.LAST_WEEK
        timestampMs >= thisMonthStart -> SessionDateGroup.THIS_MONTH
        else -> SessionDateGroup.OLDER
    }
}

private fun groupSessionsByDate(
    sessions: List<ChatSessionUiModel>,
): List<Pair<SessionDateGroup, List<ChatSessionUiModel>>> {
    val nowMs = System.currentTimeMillis()
    val grouped = sessions
        .sortedByDescending { it.updatedAtEpochMs }
        .groupBy { classifyDateGroup(it.updatedAtEpochMs, nowMs) }
    return SessionDateGroup.entries.mapNotNull { group ->
        grouped[group]?.let { group to it }
    }
}

@Composable
internal fun SessionDrawer(
    state: ChatUiState,
    onCreateSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val createSessionDescription = stringResource(id = R.string.a11y_create_session)
    val groupedSessions = remember(state.sessions) {
        groupSessionsByDate(state.sessions)
    }
    var pendingDeleteSession by remember { mutableStateOf<ChatSessionUiModel?>(null) }

    pendingDeleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            title = { Text(text = stringResource(id = R.string.ui_session_delete_title)) },
            text = { Text(text = stringResource(id = R.string.ui_session_delete_body, session.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteSession = null
                        onDeleteSession(session.id)
                    },
                ) {
                    Text(
                        text = stringResource(id = R.string.ui_session_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSession = null }) {
                    Text(text = stringResource(id = R.string.ui_session_delete_cancel))
                }
            },
        )
    }

    LazyColumn {
        item {
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
        }

        if (state.sessions.isEmpty()) {
            item {
                Text(
                    text = stringResource(id = R.string.ui_no_sessions_yet),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        groupedSessions.forEach { (group, sessions) ->
            item(key = "header-${group.name}") {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    isActive = session.id == state.activeSessionId,
                    onSwitchSession = onSwitchSession,
                    onDeleteSession = { pendingDeleteSession = session },
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSessionUiModel,
    isActive: Boolean,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
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
    val subtitle = if (session.messageCount > 0) {
        "${session.messageCount} messages"
    } else {
        stringResource(id = R.string.ui_session_no_messages)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onSwitchSession(session.id) }
            .semantics {
                selected = isActive
                stateDescription = activeStateDescription
                contentDescription = switchSessionDescription
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            modifier = Modifier.semantics {
                contentDescription = deleteSessionDescription
            },
            onClick = { onDeleteSession(session.id) },
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
