package com.syncit.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syncit.app.social.WatchTogether
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassRow
import com.syncit.app.ui.glass.PlatformBadge
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.Friend
import com.syncit.core.model.WatchedTogether

/** Puts [text] on the clipboard. False if the clipboard isn't available. */
internal fun copyToClipboard(context: Context, text: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
    return true
}

/** Android 13+ confirms copies itself (a clipboard preview), so we don't show our own notice. */
internal fun systemShowsCopies(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Long-press menu for a message, WhatsApp-style: Copy, Forward, Delete. A null action hides its
 * row (deleted messages and watched-together cards can only be deleted for me).
 */
@Composable
internal fun MessageActionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            onCopy?.let { GlassRow("Copy", icon = CopyIcon, onClick = it) }
            onForward?.let { GlassRow("Forward", icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = it) }
            onDelete?.let { GlassRow("Delete", icon = Icons.Filled.Delete, onClick = it) }
        }
    }
}

/**
 * "Delete message?": Delete for me always; Delete for everyone only for our own messages that
 * aren't deleted yet ([canDeleteForEveryone]).
 */
@Composable
internal fun DeleteMessageSheet(
    visible: Boolean,
    canDeleteForEveryone: Boolean,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = Glass.palette
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text("Delete message?", style = MaterialTheme.typography.titleLarge, color = p.textPrimary)
        Text(
            if (canDeleteForEveryone) {
                "Delete for everyone replaces it with \"This message was deleted\" for both of you."
            } else {
                "It's removed from this chat on your devices only."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
        if (canDeleteForEveryone) {
            GlassButton("Delete for everyone", onClick = onDeleteForEveryone, style = GlassButtonStyle.Danger, modifier = Modifier.fillMaxWidth())
        }
        GlassButton("Delete for me", onClick = onDeleteForMe, style = GlassButtonStyle.Danger, modifier = Modifier.fillMaxWidth())
        GlassButton("Cancel", onClick = onDismiss, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
    }
}

/** Pick one or more friends to forward a message to. [onSend] gets their uids in list order. */
@Composable
internal fun ForwardSheet(
    visible: Boolean,
    friends: List<Friend>,
    onSend: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = Glass.palette
    val haptics = LocalHapticFeedback.current
    // Starts empty every time the sheet opens.
    var selected by remember(visible) { mutableStateOf(emptySet<String>()) }
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text("Forward to…", style = MaterialTheme.typography.titleLarge, color = p.textPrimary)
        if (friends.isEmpty()) {
            Text("Add friends from Home to forward messages to them.", style = MaterialTheme.typography.bodyMedium, color = p.textTertiary)
        }
        for (friend in friends) {
            val uid = friend.profile.uid
            val checked = uid in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .toggleable(checked, role = Role.Checkbox) { on ->
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        selected = if (on) selected + uid else selected - uid
                    }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(friend.profile.displayName, size = 40.dp, online = friend.presence?.online == true, photoUrl = friend.profile.photoUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(friend.profile.displayName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${friend.profile.username}", style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 1)
                }
                CheckDot(checked)
            }
        }
        val count = friends.count { it.profile.uid in selected }
        GlassButton(
            if (count <= 1) "Send" else "Send to $count",
            onClick = { onSend(friends.map { it.profile.uid }.filter { it in selected }) },
            enabled = count > 0,
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Round check mark: accent gradient with a tick when selected, an empty glass ring otherwise. */
@Composable
private fun CheckDot(checked: Boolean) {
    val p = Glass.palette
    val surface = if (checked) {
        Modifier.clip(CircleShape).background(p.accent)
    } else {
        Modifier.glass(CircleShape)
    }
    Box(Modifier.size(24.dp).then(surface), contentAlignment = Alignment.Center) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(16.dp))
    }
}

/**
 * Centred system card in the DM timeline: "Watched Leo together" over "Netflix · 1h 42m · 10:42 pm".
 * Long-press offers Delete for me.
 */
@Composable
internal fun WatchedTogetherCard(card: WatchedTogether, time: String, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val p = Glass.palette
    val headline = WatchTogether.headline(card)
    val details = listOfNotNull(card.service?.displayName, WatchTogether.formatDuration(card.durationMs), time).joinToString(" · ")
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .glass(RoundedCornerShape(16.dp))
                .onLongPress(onLongPress)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clearAndSetSemantics { contentDescription = "$headline, $details" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            card.service?.let {
                PlatformBadge(it)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = p.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
                Text(details, style = MaterialTheme.typography.labelSmall, color = p.textSecondary, maxLines = 1)
            }
        }
    }
}

/** Long-press only (a tap does nothing), no ripple: the sheet that opens is the feedback. */
private fun Modifier.onLongPress(onLongPress: (() -> Unit)?): Modifier =
    if (onLongPress == null) {
        this
    } else {
        combinedClickable(
            interactionSource = null,
            indication = null,
            onLongClickLabel = "Options",
            onLongClick = onLongPress,
            onClick = {},
        )
    }

/** Material's "content copy" icon (not in material-icons-core). */
private val CopyIcon: ImageVector by lazy {
    materialIcon(name = "Filled.ContentCopy") {
        materialPath {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineToRelative(14f)
            close()
        }
    }
}
