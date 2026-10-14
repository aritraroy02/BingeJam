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
                    name = request.fromDisplayName,
                    text = "${request.fromDisplayName} wants to join you" +
                        (request.title?.let { " watching $it" } ?: " on ${request.app.displayName}"),
                    more = (requests.size - 1).takeIf { it > 0 }?.let { "+$it more" },
                    accept = "Accept",
                    decline = "Decline",
                    busy = busy == request.fromUid,
                    onAccept = { viewModel.accept(request) },
                    onDecline = { viewModel.decline(request) },
                )
                invite != null -> Banner(
                    name = invite.fromDisplayName,
                    text = "${invite.fromDisplayName} invited you to watch" +
                        (invite.title?.let { " $it" } ?: invite.app?.let { " on ${it.displayName}" } ?: " together"),
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
    name: String,
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
            .glass(RoundedCornerShape(26.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                more?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.textSecondary) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(decline, onClick = onDecline, style = GlassButtonStyle.Secondary, enabled = !busy, modifier = Modifier.weight(1f))
            GlassButton(accept, onClick = onAccept, loading = busy, modifier = Modifier.weight(1f))
        }
    }
}
