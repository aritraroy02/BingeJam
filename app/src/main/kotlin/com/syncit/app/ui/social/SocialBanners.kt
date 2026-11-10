package com.syncit.app.ui.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.social.RoomWording
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass

/** Join requests and room invites as a glass card at the top of whatever screen is open. */
@Composable
fun SocialBanners(viewModel: SocialEventsViewModel) {
    val requests by viewModel.joinRequests.collectAsStateWithLifecycle()
    val invites by viewModel.roomInvites.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        val request = requests.firstOrNull()
        val invite = if (request == null) invites.firstOrNull() else null
        AnimatedVisibility(
            visible = request != null || invite != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        ) {
            when {
                request != null -> Banner(
                    username = request.fromUsername,
                    displayName = request.fromDisplayName,
                    text = RoomWording.joinRequestText(request),
                    more = (requests.size - 1).takeIf { it > 0 }?.let { "+$it more" },
                    accept = "Accept",
                    decline = "Decline",
                    busy = busy == request.fromUid,
                    onAccept = { viewModel.accept(request) },
                    onDecline = { viewModel.decline(request) },
                )
                invite != null -> Banner(
                    username = invite.fromUsername,
                    displayName = invite.fromDisplayName,
                    text = RoomWording.inviteText(invite),
                    more = (invites.size - 1).takeIf { it > 0 }?.let { "+$it more" },
                    accept = "Join",
                    decline = "Not now",
                    busy = busy == invite.fromUid,
                    onAccept = { viewModel.joinInvite(invite) },
                    onDecline = { viewModel.dismissInvite(invite) },
                )
            }
        }
        NoticeBanner(notice, viewModel::dismissNotice, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun Banner(
    username: String,
    displayName: String,
    text: String,
    more: String?,
    accept: String,
    decline: String,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val p = Glass.palette
    Column(
        Modifier
            .padding(12.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(username, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Username first: it's the unique handle; display names are free text.
                Text(text, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                val details = listOfNotNull(displayName.takeIf { it.isNotBlank() }, more).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(decline, onClick = onDecline, style = GlassButtonStyle.Secondary, enabled = !busy, modifier = Modifier.weight(1f))
            GlassButton(accept, onClick = onAccept, loading = busy, modifier = Modifier.weight(1f))
        }
    }
}
