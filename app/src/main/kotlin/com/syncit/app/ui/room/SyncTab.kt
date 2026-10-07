package com.syncit.app.ui.room

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.app.permissions.Permissions
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassSegmented
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.SyncStatusLabels
import com.syncit.core.model.Room
import com.syncit.core.model.RoomTitle
import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncMode
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncSessionState

@Composable
fun SyncTab(
    room: Room,
    sync: SyncSessionState,
    isHost: Boolean,
    onResume: () -> Unit,
    onStartCountdown: () -> Unit,
    onSaveTitle: (StreamingService, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusPanel(sync, onResume)
        CountdownPanel(prominent = sync.mode == SyncMode.MANUAL_COUNTDOWN, onStart = onStartCountdown)
        SectionLabel("Movie link (optional)", Modifier.padding(top = 4.dp))
        TitlePanel(title = room.title, isHost = isHost, onSave = onSaveTitle)
        SectionLabel("While watching", Modifier.padding(top = 4.dp))
        BubblePanel(roomCode = room.code)
    }
}

@Composable
private fun StatusPanel(state: SyncSessionState, onResume: () -> Unit) {
    val context = LocalContext.current
    val p = Glass.palette
    val appName = state.service?.displayName ?: "Netflix or JioHotstar"
    GlassPanel {
        Text(SyncStatusLabels.label(state.status, appName), style = MaterialTheme.typography.titleMedium, color = state.status.color())
        when (state.status) {
            SyncStatus.APP_NOT_OPEN, SyncStatus.SESSION_LOST ->
                Text("Open the movie in $appName and start it. Sync it picks it up automatically.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            SyncStatus.NO_NOTIFICATION_ACCESS ->
                GlassButton("Turn on Notification Access", onClick = { Permissions.openNotificationAccessSettings(context) }, icon = Icons.Filled.Notifications, modifier = Modifier.fillMaxWidth())
            SyncStatus.NOT_CONFIGURED ->
                Text("Sync for $appName isn't set up in this version. Use the countdown below.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            SyncStatus.IDLE ->
                GlassButton("Resume syncing", onClick = onResume, icon = Icons.Filled.Refresh, modifier = Modifier.fillMaxWidth())
            SyncStatus.SYNCING ->
                Text("Play, pause and skips are shared with everyone in the room.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            else -> Unit
        }
        SyncStatusLabels.modeNotice(state.mode, appName)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
        state.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger) }
    }
}

@Composable
private fun CountdownPanel(prominent: Boolean, onStart: () -> Unit) {
    val p = Glass.palette
    GlassPanel {
        Text("3, 2, 1 countdown", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
        Text(
            if (prominent) {
                "This app can't be controlled from here, so start together: get everyone to the same moment, paused, then press play on \"PLAY\"."
            } else {
                "A manual backup: everyone sees the same countdown and presses play together."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
        GlassButton(
            "Start countdown",
            onClick = onStart,
            icon = Icons.Filled.PlayArrow,
            style = if (prominent) GlassButtonStyle.Primary else GlassButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Host: pick the service and paste the title's share link. Everyone: "Open movie". Optional
 * now that the hero shows what the host is playing, but handy for getting everyone onto the
 * same title.
 */
@Composable
private fun TitlePanel(title: RoomTitle?, isHost: Boolean, onSave: (StreamingService, String) -> Unit) {
    val context = LocalContext.current
    val p = Glass.palette
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }

    GlassPanel {
        if (title == null) {
            Text(
                if (isHost) "Share the movie's link so everyone can open the same title." else "The host hasn't shared a link.",
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
            )
        } else {
            Text(title.service.displayName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
            Text(title.deepLink, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            GlassButton("Open in ${title.service.displayName}", onClick = { confirmOpen = true }, modifier = Modifier.fillMaxWidth())
        }
        if (isHost) {
            if (editing || title == null) {
                TitleEditor(
                    initial = title,
                    onSave = { service, link ->
                        onSave(service, link)
                        editing = false
                    },
                    onCancel = if (title != null) ({ editing = false }) else null,
                )
            } else {
                GlassButton("Change link", onClick = { editing = true }, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmOpen && title != null) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Same title for everyone?") },
            text = {
                Text(
                    "This opens ${title.service.displayName} on the room's title. Before pressing play, check you all " +
                        "see the same movie or episode.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    if (!openTitle(context, title)) Toast.makeText(context, "No app can open this link", Toast.LENGTH_LONG).show()
                }) { Text("Open ${title.service.displayName}") }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TitleEditor(initial: RoomTitle?, onSave: (StreamingService, String) -> Unit, onCancel: (() -> Unit)?) {
    val p = Glass.palette
    var service by rememberSaveable { mutableStateOf(initial?.service ?: StreamingService.NETFLIX) }
    var link by rememberSaveable { mutableStateOf(initial?.deepLink.orEmpty()) }
    val check = TitleLinks.check(service, link)

    GlassSegmented(
        options = StreamingService.entries.map { it.displayName },
        selected = service.ordinal,
        onSelect = { service = StreamingService.entries[it] },
    )
    GlassTextField(
        value = link,
        onValueChange = { link = it.trim() },
        placeholder = "Paste the share link",
        modifier = Modifier.fillMaxWidth(),
    )
    check.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.warning) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassButton("Save for everyone", onClick = { onSave(service, link) }, enabled = check.valid, modifier = Modifier.weight(1f))
        onCancel?.let { GlassButton("Cancel", onClick = it, style = GlassButtonStyle.Secondary) }
    }
}

/** Starts/stops the floating chat bubble shown over Netflix/JioHotstar. */
@Composable
private fun BubblePanel(roomCode: String) {
    val context = LocalContext.current
    val p = Glass.palette
    GlassPanel {
        Text("Chat bubble over the movie", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
        Text(
            "Chat and react without leaving the movie." +
                if (!Permissions.canDrawOverlays(context)) " Needs \"Display over other apps\" for Sync it." else "",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                "Show bubble",
                onClick = {
                    if (Permissions.canDrawOverlays(context)) {
                        ChatBubbleService.start(context, roomCode)
                    } else {
                        // SYSTEM_ALERT_WINDOW is a special-access setting; the user must flip it.
                        Permissions.openOverlaySettings(context)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            GlassButton("Hide", onClick = { ChatBubbleService.stop(context) }, style = GlassButtonStyle.Secondary)
        }
    }
}
