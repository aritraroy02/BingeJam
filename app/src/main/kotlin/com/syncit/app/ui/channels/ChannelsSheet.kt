package com.syncit.app.ui.channels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.chat.GroupChatViewModel
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.theme.Glass
import com.syncit.core.social.ChannelKind
import com.syncit.core.social.GroupChannel
import com.syncit.core.social.GroupChannels
import com.syncit.voice.R
import kotlinx.coroutines.delay

/**
 * The group's channels, Discord style (2026-11-25, same as the PC's channel column): text channels
 * with unread dots, voice channels with who's in, the call timer and status. Admins add channels
 * with + and rename or delete one with a long press.
 */
@Composable
fun ChannelsSheet(
    visible: Boolean,
    groupName: String,
    ui: GroupChatViewModel.ChannelsUi,
    selectedText: String,
    serverNow: () -> Long,
    onDismiss: () -> Unit,
    onSelectText: (String) -> Unit,
    onOpenVoice: (String) -> Unit,
    onCreate: (String, ChannelKind) -> Unit,
    onRename: (GroupChannel, String) -> Unit,
    onDelete: (GroupChannel) -> Unit,
) {
    val p = Glass.palette
    var adding by remember { mutableStateOf<ChannelKind?>(null) }
    var editing by remember { mutableStateOf<GroupChannel?>(null) }
    // Call timers tick while anyone is in a voice channel.
    var now by remember { mutableLongStateOf(serverNow()) }
    val anyone = ui.voice.any { it.people.isNotEmpty() }
    LaunchedEffect(visible, anyone) {
        while (visible && anyone) {
            now = serverNow()
            delay(1_000)
        }
    }

    GlassBottomSheet(visible, onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(groupName, style = MaterialTheme.typography.titleLarge, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(12.dp))
            Header("Text channels", if (ui.canManage) ({ adding = ChannelKind.TEXT }) else null)
            ui.text.forEach { row ->
                ChannelRow(
                    selected = row.channel.id == selectedText,
                    onClick = {
                        onSelectText(row.channel.id)
                        onDismiss()
                    },
                    onLongClick = if (ui.canManage && !row.channel.builtIn) ({ editing = row.channel }) else null,
                ) {
                    Text("#", style = MaterialTheme.typography.titleMedium, color = p.textTertiary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        row.channel.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (row.unread || row.channel.id == selectedText) p.textPrimary else p.textSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.unread) Box(Modifier.size(8.dp).clip(CircleShape).background(p.textPrimary))
                }
            }
            Spacer(Modifier.size(12.dp))
            Header("Voice channels", if (ui.canManage) ({ adding = ChannelKind.VOICE }) else null)
            ui.voice.forEach { row ->
                ChannelRow(
                    selected = row.connected,
                    onClick = { onOpenVoice(row.channel.id) },
                    onLongClick = if (ui.canManage && !row.channel.builtIn) ({ editing = row.channel }) else null,
                ) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_voice_headset), null, tint = p.textTertiary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        row.channel.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (row.people.isNotEmpty()) p.textPrimary else p.textSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ChannelModel.callElapsed(row.people, now)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = p.textTertiary)
                    }
                }
                row.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = p.textTertiary, modifier = Modifier.padding(start = 46.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                row.people.forEach { person ->
                    Row(Modifier.fillMaxWidth().padding(start = 46.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(person.name, size = 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(person.name, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (person.live) LiveBadge()
                        if (person.deafened || person.muted) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                ImageVector.vectorResource(if (person.deafened) R.drawable.ic_voice_headset_off else R.drawable.ic_voice_mic_off),
                                if (person.deafened) "Deafened" else "Muted",
                                tint = p.textTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
            if (ui.canManage) {
                Text(
                    "Long-press a channel you added to rename or delete it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textTertiary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    adding?.let { kind ->
        NameDialog(
            title = if (kind == ChannelKind.TEXT) "New text channel" else "New voice channel",
            initial = "",
            confirm = "Add",
            onDismiss = { adding = null },
            onConfirm = { name ->
                adding = null
                onCreate(name, kind)
            },
        )
    }
    editing?.let { channel ->
        var confirmDelete by remember(channel.id) { mutableStateOf(false) }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Delete ${ChannelModel.label(channel)}?") },
                text = { Text(if (channel.kind == ChannelKind.TEXT) "Its messages are deleted for everyone." else "Anyone in it is disconnected.") },
                confirmButton = {
                    TextButton(onClick = {
                        editing = null
                        onDelete(channel)
                    }) { Text("Delete", color = p.danger) }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            )
        } else {
            NameDialog(
                title = "Edit ${ChannelModel.label(channel)}",
                initial = channel.name,
                confirm = "Save",
                onDismiss = { editing = null },
                onConfirm = { name ->
                    editing = null
                    onRename(channel, name)
                },
                extra = { TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = p.danger) } },
            )
        }
    }
}

@Composable
private fun Header(text: String, onAdd: (() -> Unit)?) {
    val p = Glass.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = p.textTertiary, modifier = Modifier.weight(1f))
        if (onAdd != null) GlassIconButton(Icons.Filled.Add, "Add a channel", onAdd, plain = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val p = Glass.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) p.raised else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** "LIVE": someone is sharing their screen (a PC's Go Live). */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val p = Glass.palette
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(p.danger).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text("LIVE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            GlassTextField(value = name, onValueChange = { name = it.take(GroupChannels.NAME_MAX) }, placeholder = "Channel name", singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                extra?.invoke()
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
