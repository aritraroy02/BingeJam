package com.syncit.app.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syncit.app.permissions.Permissions
import com.syncit.app.ui.theme.StatusColors
import com.syncit.core.config.SyncStatusLabels
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncSessionState

/** "Netflix not open" / "Session not found" / "Syncing" / "Out of sync, correcting", plus fallback notices. */
@Composable
fun SyncStatusCard(state: SyncSessionState, onResume: () -> Unit) {
    val context = LocalContext.current
    val appName = state.service?.displayName ?: "Netflix or JioHotstar"
    val healthy = state.status == SyncStatus.SYNCING
    val color = when (state.status) {
        SyncStatus.SYNCING -> StatusColors.Online
        SyncStatus.CORRECTING, SyncStatus.CATCHING_UP, SyncStatus.WAITING_FOR_ROOM -> StatusColors.Warning
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (healthy) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SyncStatusLabels.label(state.status, appName), color = color, fontWeight = FontWeight.Bold)

            when (state.status) {
                SyncStatus.APP_NOT_OPEN, SyncStatus.SESSION_LOST ->
                    Text("Open the movie in $appName and start it. Sync it picks it up automatically.")
                SyncStatus.NO_NOTIFICATION_ACCESS ->
                    Button(onClick = { Permissions.openNotificationAccessSettings(context) }) { Text("Turn on Notification Access") }
                SyncStatus.NOT_CONFIGURED ->
                    Text("Its package name hasn't been set yet (see RUN_ON_MY_MACHINE.md, Phase 0). Use the countdown below meanwhile.")
                SyncStatus.IDLE ->
                    Button(onClick = onResume) { Text("Resume syncing") }
                else -> Unit
            }

            SyncStatusLabels.modeNotice(state.mode, appName)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            state.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
