package com.syncit.app.ui.room

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassSmallButton
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.Friend

/** Pick friends to invite into this room (in-app invite banner on their side), or share the link. */
@Composable
fun InviteFriendsSheet(
    visible: Boolean,
    /** Music/YouTube room: "Invite to jam". */
    jam: Boolean,
    friends: List<Friend>,
    invited: Set<String>,
    memberUids: Set<String>,
    onInvite: (String) -> Unit,
    onShareLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = Glass.palette
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text(if (jam) "Invite to jam" else "Invite friends", style = MaterialTheme.typography.titleLarge, color = p.textPrimary)
        Text(
            if (jam) {
                "They'll see an invite in the app. When they join, your track starts on their phone and they follow you from track to track."
            } else {
                "They'll see an invite in the app with what you're watching."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
        if (friends.isEmpty()) {
            Text("Add friends from Home to invite them here.", style = MaterialTheme.typography.bodyMedium, color = p.textTertiary)
        }
        val sorted = friends.sortedWith(compareBy({ it.presence?.online != true }, { it.profile.displayName.lowercase() }))
        for (friend in sorted) {
            val uid = friend.profile.uid
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(friend.profile.displayName, size = 40.dp, online = friend.presence?.online == true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(friend.profile.displayName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${friend.profile.username}", style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 1)
                }
                when {
                    uid in memberUids -> GlassSmallButton("In the room", onClick = {}, enabled = false, primary = false)
                    uid in invited -> GlassSmallButton("Invited", onClick = {}, enabled = false, primary = false)
                    else -> GlassSmallButton("Invite", onClick = { onInvite(uid) })
                }
            }
        }
        GlassButton(
            "Share invite link",
            onClick = onShareLink,
            icon = Icons.Filled.Share,
            style = GlassButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
