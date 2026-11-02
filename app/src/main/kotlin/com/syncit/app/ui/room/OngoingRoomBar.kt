package com.syncit.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.ui.theme.Glass
import com.syncit.core.voice.VoiceStatus
import com.syncit.voice.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The room you're still in after leaving the room screen (back, Home, a chat…). Sync and voice keep
 * running in the background, so, like WhatsApp's ongoing-call bar, every other screen shows it on
 * top: time in the room, mute, and Leave. Tapping it goes back to the room.
 */
class OngoingRoomViewModel(private val container: AppContainer) : ViewModel() {

    data class Ongoing(
        val code: String,
        /** Wall-clock time we entered this room (kept across app restarts). */
        val sinceMs: Long,
        val inVoice: Boolean,
        val muted: Boolean,
    )

    private val roomCode = container.sessionController.state.map { it.roomCode }.distinctUntilChanged()

    val ongoing: StateFlow<Ongoing?> = combine(roomCode, container.voice.state) { code, voice ->
        code ?: return@combine null
        val inVoice = voice.roomCode == code && voice.status != VoiceStatus.OFF && voice.status != VoiceStatus.FAILED
        Ongoing(code, joinedAt(code), inVoice, voice.muted)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            roomCode.collect { if (it == null) container.prefs.roomJoinedAt = null }
        }
    }

    private fun joinedAt(code: String): Long {
        val saved = container.prefs.roomJoinedAt
        if (saved != null && saved.first == code) return saved.second
        val now = System.currentTimeMillis()
        container.prefs.roomJoinedAt = code to now
        return now
    }

    fun setMuted(muted: Boolean) = container.voice.setMuted(muted)

    /** Same as Leave in the room: voice, sync, then the room itself (best effort when offline). */
    fun leave() {
        val code = ongoing.value?.code ?: return
        if (container.voice.state.value.roomCode == code) container.voice.leave()
        container.sessionController.stop()
        viewModelScope.launch {
            try {
                container.transport.leaveRoom(code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: the server-side onDisconnect marks us offline anyway.
            }
            container.prefs.lastRoomCode = null
        }
    }
}

/** "In a room · 05:12" across the top, under the status bar. */
@Composable
fun OngoingRoomBar(
    ongoing: OngoingRoomViewModel.Ongoing,
    onOpen: () -> Unit,
    onMute: (Boolean) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ongoing.sinceMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000 - now % 1_000)
        }
    }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val label = if (ongoing.inVoice) "In a room · voice on" else "In a room"
    val time = elapsed(now - ongoing.sinceMs)

    Row(
        modifier
            .fillMaxWidth()
            .background(p.success)
            .statusBarsPadding()
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "$label, $time. Tap to return to the room" }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        Text(
            "$label  $time",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (ongoing.inVoice) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(role = Role.Button) { onMute(!ongoing.muted) }
                    .semantics { contentDescription = if (ongoing.muted) "Unmute" else "Mute" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ImageVector.vectorResource(if (ongoing.muted) R.drawable.ic_voice_mic_off else R.drawable.ic_voice_mic),
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            Modifier
                .clip(CircleShape)
                .background(p.danger)
                .clickable(role = Role.Button) { confirmLeave = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ImageVector.vectorResource(R.drawable.ic_voice_leave), null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Exit", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave the room?") },
            text = { Text(if (ongoing.inVoice) "You'll stop syncing and leave voice." else "You'll stop syncing with the room.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeave()
                }) { Text("Leave", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

/** 05:12, or 1:05:12 after an hour. */
internal fun elapsed(ms: Long): String {
    val total = (ms / 1_000).coerceAtLeast(0)
    val h = total / 3_600
    val m = total % 3_600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
