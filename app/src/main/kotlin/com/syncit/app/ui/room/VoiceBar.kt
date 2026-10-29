package com.syncit.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassSmallButton
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.Friend
import com.syncit.core.model.Member
import com.syncit.core.voice.VOICE_MAX_VOLUME
import com.syncit.core.voice.VoiceQuality
import com.syncit.core.voice.VoiceState
import com.syncit.core.voice.VoiceStatus
import com.syncit.voice.R
import kotlin.math.roundToInt

/** The mic icon for the room's top bar: join voice, or mute/unmute once in. */
@Composable
fun voiceIcon(inVoice: Boolean, muted: Boolean): ImageVector = ImageVector.vectorResource(
    if (inVoice && muted) R.drawable.ic_voice_mic_off else R.drawable.ic_voice_mic,
)

/**
 * Discord-style voice controls for the room. Not in voice: who is talking, with Join. In voice:
 * everyone with a speaking ring (tap someone for their volume, yourself for your mic volume),
 * HD/SD, Mute, Deafen and Leave.
 */
@Composable
fun VoiceBar(
    voice: VoiceState,
    roomCode: String,
    members: List<Member>,
    myUid: String?,
    myName: String,
    friends: List<Friend>,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onMute: (Boolean) -> Unit,
    onDeafen: (Boolean) -> Unit,
    onMicVolume: (Float) -> Unit,
    onPeerVolume: (String, Float) -> Unit,
    onQuality: (VoiceQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    val inVoice = voice.active && voice.roomCode == roomCode
    val photos = remember(friends) { friends.associate { it.profile.uid to it.profile.photoUrl } }

    if (!inVoice) {
        val talking = members.filter { it.inVoice && it.online && it.uid != myUid }
        if (talking.isEmpty()) return
        GlassPanel(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(R.drawable.ic_voice_mic), null, tint = p.success, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "${names(talking.map { it.name })} ${if (talking.size == 1) "is" else "are"} in voice",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                GlassSmallButton("Join", onClick = onJoin)
            }
        }
        return
    }

    var volumeFor by rememberSaveable { mutableStateOf<String?>(null) }

    GlassPanel(modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (voice.status) {
                    VoiceStatus.CONNECTING -> "Connecting to voice…"
                    VoiceStatus.RECONNECTING -> "Reconnecting voice…"
                    else -> if (voice.peers.isEmpty()) "In voice · waiting for others" else "In voice"
                },
                style = MaterialTheme.typography.labelLarge,
                color = p.textSecondary,
                modifier = Modifier.weight(1f),
            )
            QualityChip("HD", voice.quality == VoiceQuality.HD) { onQuality(VoiceQuality.HD) }
            Spacer(Modifier.width(6.dp))
            QualityChip("SD", voice.quality == VoiceQuality.SD) { onQuality(VoiceQuality.SD) }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Speaker(
                name = "You",
                avatarName = myName,
                photoUrl = myUid?.let { photos[it] },
                speaking = voice.speaking,
                muted = voice.muted,
                deafened = voice.deafened,
                onClick = { volumeFor = ME },
            )
            for (peer in voice.peers) {
                Speaker(
                    name = peer.name.substringBefore(' '),
                    avatarName = peer.name,
                    photoUrl = photos[peer.uid],
                    speaking = peer.speaking,
                    muted = peer.muted,
                    deafened = peer.deafened,
                    onClick = { volumeFor = peer.uid },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassIconButton(
                ImageVector.vectorResource(if (voice.muted) R.drawable.ic_voice_mic_off else R.drawable.ic_voice_mic),
                if (voice.muted) "Unmute" else "Mute",
                onClick = { onMute(!voice.muted) },
            )
            GlassIconButton(
                ImageVector.vectorResource(if (voice.deafened) R.drawable.ic_voice_headset_off else R.drawable.ic_voice_headset),
                if (voice.deafened) "Undeafen" else "Deafen",
                onClick = { onDeafen(!voice.deafened) },
            )
            Text(
                when {
                    voice.deafened -> "Deafened"
                    voice.muted -> "Muted"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = p.danger,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(p.danger).clickable(onClick = onLeave),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ImageVector.vectorResource(R.drawable.ic_voice_leave), "Leave voice", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }

    volumeFor?.let { who ->
        val peer = voice.peers.firstOrNull { it.uid == who }
        if (who != ME && peer == null) {
            volumeFor = null
            return@let
        }
        VolumeDialog(
            title = if (peer == null) "Your mic volume" else "${peer.name}'s volume",
            note = if (peer == null) "How loud you are for everyone." else "Only changes what you hear.",
            value = peer?.volume ?: voice.micVolume,
            onChange = { v -> if (peer == null) onMicVolume(v) else onPeerVolume(peer.uid, v) },
            onDismiss = { volumeFor = null },
        )
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = Glass.palette
    val shape = CircleShape
    Box(
        Modifier
            .clip(shape)
            .then(if (selected) Modifier.background(p.accent) else Modifier.border(1.dp, p.textSecondary.copy(alpha = 0.4f), shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) p.onAccent else p.textSecondary)
    }
}

@Composable
private fun Speaker(
    name: String,
    avatarName: String,
    photoUrl: String?,
    speaking: Boolean,
    muted: Boolean,
    deafened: Boolean,
    onClick: () -> Unit,
) {
    val p = Glass.palette
    Column(
        Modifier.widthIn(max = 64.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Avatar(
                avatarName,
                size = 44.dp,
                photoUrl = photoUrl,
                modifier = Modifier.border(2.dp, if (speaking) p.success else Color.Transparent, CircleShape).padding(3.dp),
            )
            if (muted || deafened) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(p.danger),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        ImageVector.vectorResource(if (deafened) R.drawable.ic_voice_headset_off else R.drawable.ic_voice_mic_off),
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Text(name, style = MaterialTheme.typography.labelSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VolumeDialog(title: String, note: String, value: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    val p = Glass.palette
    var v by remember { mutableFloatStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(note, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = v,
                        onValueChange = { v = it },
                        onValueChangeFinished = { onChange(v) },
                        valueRange = 0f..VOICE_MAX_VOLUME,
                        colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${(v * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onChange(v)
                onDismiss()
            }) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = {
                v = 1f
                onChange(1f)
            }) { Text("Reset") }
        },
    )
}

private const val ME = "me"

private fun names(list: List<String>): String {
    val first = list.map { it.substringBefore(' ') }
    return when (first.size) {
        1 -> first[0]
        2 -> "${first[0]} and ${first[1]}"
        else -> "${first[0]}, ${first[1]} and ${first.size - 2} more"
    }
}
