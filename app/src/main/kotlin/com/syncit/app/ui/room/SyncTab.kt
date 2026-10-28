package com.syncit.app.ui.room

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.syncit.app.ui.glass.GlassChip
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand
import com.syncit.core.config.SyncStatusLabels
import com.syncit.core.model.Room
import com.syncit.core.model.RoomTitle
import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncMode
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncSessionState

/**
 * [service] is what the room plays (RoomScreen: host preview, else the shared link, else our own
 * session); it decides watch-party vs jam wording.
 */
@Composable
fun SyncTab(
    room: Room,
    service: StreamingService?,
    sync: SyncSessionState,
    isHost: Boolean,
    onResume: () -> Unit,
    onStartCountdown: () -> Unit,
    onSaveTitle: (StreamingService, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val jam = service?.isJam == true
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusPanel(sync, service, onResume)
        CountdownPanel(prominent = sync.mode == SyncMode.MANUAL_COUNTDOWN, onStart = onStartCountdown)
        SectionLabel(if (jam) "Link (optional)" else "Movie link (optional)", Modifier.padding(top = 4.dp))
        TitlePanel(title = room.title, isHost = isHost, jam = jam, roomService = service, onSave = onSaveTitle)
        SectionLabel(if (jam) "While it plays" else "While watching", Modifier.padding(top = 4.dp))
        BubblePanel(roomCode = room.code, jam = jam)
    }
}

@Composable
private fun StatusPanel(state: SyncSessionState, roomService: StreamingService?, onResume: () -> Unit) {
    val context = LocalContext.current
    val p = Glass.palette
    val service = state.service ?: roomService
    val jam = service?.isJam == true
    val appName = service?.displayName ?: "your streaming app"
    GlassPanel {
        Text(MemberPrompts.statusLabel(state.status, service), style = MaterialTheme.typography.titleMedium, color = state.status.color())
        when (state.status) {
            SyncStatus.APP_NOT_OPEN, SyncStatus.SESSION_LOST -> Text(
                if (jam) {
                    "Open $appName. ${Brand.NAME} starts the host's track and keeps you on it."
                } else {
                    "Open the movie in $appName and start it. ${Brand.NAME} picks it up automatically."
                },
                color = p.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            SyncStatus.NO_NOTIFICATION_ACCESS ->
                GlassButton("Turn on Notification Access", onClick = { Permissions.openNotificationAccessSettings(context) }, icon = Icons.Filled.Notifications, modifier = Modifier.fillMaxWidth())
            SyncStatus.NOT_CONFIGURED ->
                Text("Sync for $appName isn't set up in this version. Use the countdown below.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            SyncStatus.IDLE ->
                GlassButton("Resume syncing", onClick = onResume, icon = Icons.Filled.Refresh, modifier = Modifier.fillMaxWidth())
            SyncStatus.SYNCING -> Text(
                if (jam) {
                    "Play, pause and skips are shared with everyone, and everyone follows the host's track changes."
                } else {
                    "Play, pause and skips are shared with everyone in the room."
                },
                color = p.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
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
 * now that the hero shows what the host is playing (and jams follow the host's track on their
 * own), but handy for getting everyone onto the same title.
 */
@Composable
private fun TitlePanel(
    title: RoomTitle?,
    isHost: Boolean,
    jam: Boolean,
    roomService: StreamingService?,
    onSave: (StreamingService, String) -> Unit,
) {
    val context = LocalContext.current
    val p = Glass.palette
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }

    GlassPanel {
        if (title == null) {
            Text(
                when {
                    !isHost -> "The host hasn't shared a link."
                    jam -> "Not needed for a jam: everyone follows your track automatically. Share a link only if someone can't start it."
                    else -> "Share the movie's link so everyone can open the same title."
                },
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
                    defaultService = roomService,
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
        val what = if (title.service.isJam) "song or video" else "movie or episode"
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Same title for everyone?") },
            text = {
                Text(
                    "This opens ${title.service.displayName} on the room's link. Before pressing play, check you all " +
                        "see the same $what.",
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

/** Seven apps don't fit a segmented control: wrapping chips, grouped into watch parties and jams. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleEditor(
    initial: RoomTitle?,
    defaultService: StreamingService?,
    onSave: (StreamingService, String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val p = Glass.palette
    var service by rememberSaveable { mutableStateOf(initial?.service ?: defaultService ?: StreamingService.NETFLIX) }
    var link by rememberSaveable { mutableStateOf(initial?.deepLink.orEmpty()) }
    val check = TitleLinks.check(service, link)

    for (jamGroup in listOf(false, true)) {
        Text(if (jamGroup) "Jam" else "Watch party", style = MaterialTheme.typography.labelMedium, color = p.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in StreamingService.entries.filter { it.isJam == jamGroup }) {
                GlassChip(option.displayName, onClick = { service = option }, selected = option == service)
            }
        }
    }
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

/** Starts/stops the floating chat bubble shown over the streaming or music app. */
@Composable
private fun BubblePanel(roomCode: String, jam: Boolean) {
    val context = LocalContext.current
    val p = Glass.palette
    GlassPanel {
        Text(if (jam) "Chat bubble over the app" else "Chat bubble over the movie", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
        Text(
            (if (jam) "Chat and react without leaving the app." else "Chat and react without leaving the movie.") +
                if (!Permissions.canDrawOverlays(context)) " Needs \"Display over other apps\" for ${Brand.NAME}." else "",
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
