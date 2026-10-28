package com.syncit.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.push.PushNotifications
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.room.ChatInputBar
import com.syncit.app.ui.room.MessageBubble
import com.syncit.app.ui.theme.Glass

@Composable
fun DmScreen(viewModel: DmViewModel, onBack: () -> Unit) {
    val p = Glass.palette
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val friendState by viewModel.friend.collectAsStateWithLifecycle()
    val friend = friendState.friend
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val name = profile?.displayName ?: friend?.profile?.displayName ?: ""
    val list = messages

    // Opening the chat, and each message arriving while it's open, counts as read.
    LaunchedEffect(list?.size) { viewModel.markRead() }

    // No notifications for the chat on screen, and clear the one that brought us here.
    val context = LocalContext.current
    DisposableEffect(viewModel.friendUid) {
        PushNotifications.openChatUid = viewModel.friendUid
        PushNotifications.cancelChat(context, viewModel.friendUid)
        onDispose {
            if (PushNotifications.openChatUid == viewModel.friendUid) PushNotifications.openChatUid = null
        }
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(10.dp))
                Avatar(
                    name.ifEmpty { "?" },
                    size = 40.dp,
                    online = friend?.presence?.online == true,
                    photoUrl = profile?.photoUrl ?: friend?.profile?.photoUrl,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (friendState.loaded && friend == null) "@${profile?.username.orEmpty()}" else viewModel.statusLine(friend),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                val listState = rememberLazyListState()
                LaunchedEffect(list?.size) { if (!list.isNullOrEmpty()) listState.animateScrollToItem(list.lastIndex) }
                when {
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = p.accent)
                    list.isEmpty() -> Text(
                        "Say hi to ${name.substringBefore(' ').ifEmpty { "your friend" }} 👋",
                        color = p.textSecondary,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list.orEmpty(), key = { it.id }) { msg ->
                        MessageBubble(msg.text, senderName = null, isMine = msg.fromUid == viewModel.myUid)
                    }
                }
            }

            Box(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
                if (friendState.loaded && friend == null) {
                    Text(
                        "You're not friends any more, so you can't send messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    ChatInputBar(viewModel::send, "Message ${name.substringBefore(' ')}", Modifier.padding(bottom = 8.dp))
                }
            }
        }
        NoticeBanner(error, viewModel::dismissError, Modifier.align(Alignment.BottomCenter))
    }
}
