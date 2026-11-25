package com.syncit.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.ui.theme.Glass
import com.syncit.core.voice.parseGroupVoiceRoom
import com.syncit.voice.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * "Voice connected · Gaming / Movie Night · Singapore · 42 ms" across the top of every other screen
 * while this phone talks in a group's voice channel (2026-11-25), like the PC's voice panel: tap to go
 * back to the channel, mute, deafen, leave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConnectedViewModel(private val container: AppContainer) : ViewModel() {
    data class Connected(
        val groupId: String,
        val channelId: String,
        val where: String,
        val server: String,
        val connected: Boolean,
        val muted: Boolean,
        val deafened: Boolean,
    )

    private val target = container.voice.state
        .map { s -> parseGroupVoiceRoom(s.roomCode)?.takeIf { s.active } }
        .distinctUntilChanged()

    /** "Gaming / Movie Night" for the channel we're in. */
    private val names = target.flatMapLatest { t ->
        if (t == null) flowOf(null)
        else combine(
            container.social.groups.observeGroup(t.first).catch { emit(null) },
            container.social.channels.observeChannels(t.first).catch { emit(emptyList()) },
        ) { group, stored ->
            val channel = ChannelModel.list(stored).second.firstOrNull { it.id == t.second }?.name ?: "Voice"
            "$channel / ${group?.name ?: "Group"}"
        }
    }

    val connected: StateFlow<Connected?> = combine(container.voice.state, target, names) { s, t, where ->
        t ?: return@combine null
        Connected(
            groupId = t.first,
            channelId = t.second,
            where = where ?: "Voice",
            server = ChannelModel.serverLine(s.server, s.pingMs),
            connected = s.status == com.syncit.core.voice.VoiceStatus.CONNECTED,
            muted = s.muted,
            deafened = s.deafened,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setMuted(muted: Boolean) = container.voice.setMuted(muted)
    fun setDeafened(deafened: Boolean) = container.voice.setDeafened(deafened)

    fun leave() {
        container.goLive.stop()
        container.voice.leave()
    }
}

@Composable
fun VoiceConnectedBar(
    state: VoiceConnectedViewModel.Connected,
    onOpen: () -> Unit,
    onMute: (Boolean) -> Unit,
    onDeafen: (Boolean) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    val label = if (state.connected) "Voice connected" else "Connecting…"
    Row(
        modifier
            .fillMaxWidth()
            .background(p.success)
            .statusBarsPadding()
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "$label, ${state.where}. Tap to open the voice channel" }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$label · ${state.server}",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(state.where, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarButton(if (state.muted) R.drawable.ic_voice_mic_off else R.drawable.ic_voice_mic, if (state.muted) "Unmute" else "Mute") { onMute(!state.muted) }
        BarButton(if (state.deafened) R.drawable.ic_voice_headset_off else R.drawable.ic_voice_headset, if (state.deafened) "Undeafen" else "Deafen") { onDeafen(!state.deafened) }
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(p.danger).clickable(role = Role.Button, onClick = onLeave).semantics { contentDescription = "Leave voice" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ImageVector.vectorResource(R.drawable.ic_voice_leave), null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BarButton(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(ImageVector.vectorResource(icon), null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
