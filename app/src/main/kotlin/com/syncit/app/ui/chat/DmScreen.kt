package com.syncit.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.push.PushNotifications
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.room.ChatInputBar
import com.syncit.app.ui.room.MessageBubble
import com.syncit.app.ui.theme.Glass

/** How long the typing bubble takes to grow in; the list follows it to the bottom meanwhile. */
private const val TYPING_ENTER_MS = 220

@Composable
fun DmScreen(viewModel: DmViewModel, onBack: () -> Unit) {
    val p = Glass.palette
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val friendState by viewModel.friend.collectAsStateWithLifecycle()
    val friend = friendState.friend
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val friendTyping by viewModel.friendTyping.collectAsStateWithLifecycle()
    val name = profile?.displayName ?: friend?.profile?.displayName ?: ""
    val list = messages
    val notFriends = friendState.loaded && friend == null
    val showTyping = friendTyping && !notFriends
    val rows = rememberChatRows(list.orEmpty(), { it.id }, { it.fromUid }, { it.sentAt }, viewModel::serverNowMs)

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

    // We stop "typing" when the app goes to the background or the chat closes.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onChatStopped() }
    DisposableEffect(viewModel) { onDispose { viewModel.onChatStopped() } }

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
                    // "typing…" replaces the online/last-seen line while they type.
                    Crossfade(showTyping, label = "status") { typing ->
                        Text(
                            when {
                                typing -> "typing…"
                                notFriends -> "@${profile?.username.orEmpty()}"
                                else -> viewModel.statusLine(friend)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (typing) p.accent else p.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                val listState = rememberLazyListState()
                // The typing row is always the last item (empty while hidden), at index rows.size.
                val typingIndex = rows.size
                LaunchedEffect(list?.size) { if (!list.isNullOrEmpty()) listState.animateScrollToItem(typingIndex) }
                LaunchedEffect(showTyping) {
                    if (!showTyping) return@LaunchedEffect
                    // Only follow it if the reader is at the bottom (the last message is on screen),
                    // not while they're scrolled up reading older messages.
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    if (lastVisible < rows.lastIndex) return@LaunchedEffect
                    // Keep the bottom pinned each frame while the bubble grows in.
                    val start = withFrameNanos { it }
                    while (true) {
                        val frame = withFrameNanos { it }
                        listState.scrollToItem(typingIndex)
                        if (frame - start > (TYPING_ENTER_MS + 60) * 1_000_000L) break
                    }
                }
                when {
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = p.accent)
                    list.isEmpty() && !showTyping -> Text(
                        "Say hi to ${name.substringBefore(' ').ifEmpty { "your friend" }} 👋",
                        color = p.textSecondary,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatTimes.Row.Day -> DaySeparator(row.label)
                            is ChatTimes.Row.Message -> MessageBubble(
                                row.item.text,
                                senderName = null,
                                isMine = row.item.fromUid == viewModel.myUid,
                                time = row.time,
                                grouped = row.grouped,
                                modifier = Modifier.padding(top = messageGap(row.grouped)),
                            )
                        }
                    }
                    item(key = "typing") { TypingRow(showTyping, name) }
                }
            }

            Box(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
                if (notFriends) {
                    Text(
                        "You're not friends any more, so you can't send messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    ChatInputBar(
                        viewModel::send,
                        "Message ${name.substringBefore(' ')}",
                        Modifier.padding(bottom = 8.dp),
                        onTextChange = viewModel::onDraftChanged,
                    )
                }
            }
        }
        NoticeBanner(error, viewModel::dismissError, Modifier.align(Alignment.BottomCenter))
    }
}

/** The friend's typing bubble, growing in and out at the bottom of the chat. */
@Composable
private fun TypingRow(visible: Boolean, name: String) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(TYPING_ENTER_MS)) + expandVertically(tween(TYPING_ENTER_MS), expandFrom = Alignment.Top),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top),
    ) {
        TypingBubble(name, Modifier.padding(top = 8.dp))
    }
}
