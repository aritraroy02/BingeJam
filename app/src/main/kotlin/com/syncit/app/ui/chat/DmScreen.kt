package com.syncit.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.syncit.app.ui.glass.GlassSmallButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.R
import com.syncit.app.push.PushNotifications
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassRow
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.room.ChatInputBar
import com.syncit.app.ui.safety.BlockDialog
import com.syncit.app.ui.safety.ReportSheet
import com.syncit.core.social.Safety
import com.syncit.app.ui.room.MessageBubble
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.DirectMessage

/** How long the typing bubble takes to grow in; the list follows it to the bottom meanwhile. */
private const val TYPING_ENTER_MS = 220

@Composable
fun DmScreen(
    viewModel: DmViewModel,
    onBack: () -> Unit,
    /** Opens the room; waitingFor = the friend we just invited (null when joining a live room). */
    onOpenRoom: (code: String, waitingFor: String?) -> Unit = { _, _ -> },
    onChatDeleted: () -> Unit = onBack,
    /** Tapping the picture or name opens their profile (2026-11-10). */
    onOpenProfile: () -> Unit = {},
) {
    val p = Glass.palette
    val room by viewModel.room.collectAsStateWithLifecycle()
    val startingRoom by viewModel.startingRoom.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var chatMenu by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val safetyBusy by viewModel.safetyBusy.collectAsStateWithLifecycle()
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
    // Watched-together cards never group with the messages around them.
    val rows = rememberChatRows(list.orEmpty(), { it.id }, { if (it.watched != null) "card:${it.id}" else it.fromUid }, { it.sentAt }, viewModel::serverNowMs)
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // Long-press menu → Delete / Forward. Each keeps its last message while the sheet slides away.
    var menuFor by remember { mutableStateOf<DirectMessage?>(null) }
    var deleteFor by remember { mutableStateOf<DirectMessage?>(null) }
    var forwardText by remember { mutableStateOf<String?>(null) }
    var lastMenu by remember { mutableStateOf<DirectMessage?>(null) }
    var lastDelete by remember { mutableStateOf<DirectMessage?>(null) }
    val openMenu = { message: DirectMessage ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuFor = message
        lastMenu = message
    }

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
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = "View profile", onClick = onOpenProfile),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Avatar(
                    name.ifEmpty { "?" },
                    size = 40.dp,
                    online = friend?.presence?.online == true,
                    photoUrl = profile?.photoUrl ?: friend?.profile?.photoUrl,
                )
                Spacer(Modifier.width(12.dp))
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
                // The chat's room, like WhatsApp's call button (user's request, 2026-11-05).
                if (!notFriends && friend != null) {
                    Spacer(Modifier.width(8.dp))
                    val live = room
                    if (live != null) {
                        GlassSmallButton("Join · ${live.inRoom.size}", onClick = { viewModel.joinRoom(live.code) { onOpenRoom(it, null) } })
                    } else {
                        GlassSmallButton(
                            if (startingRoom) "Starting…" else "Start room",
                            onClick = { viewModel.startRoom { code -> onOpenRoom(code, viewModel.friendUid) } },
                            enabled = !startingRoom,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Filled.MoreVert, "More", { chatMenu = true }, plain = true)
            }
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Delete this chat?") },
                    text = { Text("It's removed from your phone and your Chats list. $name still has their copy.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmClear = false
                            viewModel.clearChat(onChatDeleted)
                        }) { Text("Delete chat", color = p.danger) }
                    },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                )
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
                    list.isEmpty() && !showTyping -> Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(name.ifEmpty { "?" }, size = 72.dp, photoUrl = profile?.photoUrl ?: friend?.profile?.photoUrl)
                        Text(
                            "Say hi to ${name.substringBefore(' ').ifEmpty { "your friend" }}",
                            style = MaterialTheme.typography.titleMedium,
                            color = p.textPrimary,
                        )
                        Text(
                            "Plan your next watch party or jam here.",
                            color = p.textSecondary,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatTimes.Row.Day -> DaySeparator(row.label)
                            is ChatTimes.Row.Message -> {
                                val message = row.item
                                val mine = message.fromUid == viewModel.myUid
                                val card = message.watched
                                if (card != null) {
                                    WatchedTogetherCard(
                                        card,
                                        row.time,
                                        modifier = Modifier.padding(top = 8.dp).animateItem(),
                                        onLongPress = { openMenu(message) },
                                    )
                                } else {
                                    MessageBubble(
                                        when {
                                            !message.deleted -> message.text
                                            mine -> "You deleted this message"
                                            else -> "This message was deleted"
                                        },
                                        senderName = null,
                                        isMine = mine,
                                        time = row.time,
                                        grouped = row.grouped,
                                        forwarded = message.forwarded,
                                        deleted = message.deleted,
                                        modifier = Modifier.padding(top = messageGap(row.grouped)).animateItem(),
                                        onLongPress = { openMenu(message) },
                                    )
                                }
                            }
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

        // Copy and Forward only for real text; deleted messages and cards can only be hidden.
        val menuMessage = lastMenu
        val hasText = menuMessage != null && !menuMessage.deleted && menuMessage.watched == null
        MessageActionsSheet(
            visible = menuFor != null,
            onDismiss = { menuFor = null },
            onCopy = if (hasText) {
                {
                    menuMessage?.let { if (copyToClipboard(context, it.text) && !systemShowsCopies()) viewModel.showNotice("Message copied") }
                    menuFor = null
                }
            } else {
                null
            },
            onForward = if (hasText) {
                {
                    forwardText = menuMessage?.text
                    menuFor = null
                }
            } else {
                null
            },
            onDelete = {
                deleteFor = menuFor
                lastDelete = menuFor
                menuFor = null
            },
        )

        val toDelete = lastDelete
        DeleteMessageSheet(
            visible = deleteFor != null,
            canDeleteForEveryone = toDelete != null && toDelete.fromUid == viewModel.myUid && !toDelete.deleted && toDelete.watched == null,
            onDeleteForMe = {
                deleteFor?.let(viewModel::deleteForMe)
                deleteFor = null
            },
            onDeleteForEveryone = {
                deleteFor?.let(viewModel::deleteForEveryone)
                deleteFor = null
            },
            onDismiss = { deleteFor = null },
        )

        ForwardSheet(
            visible = forwardText != null,
            friends = forwardTargets,
            onSend = { uids ->
                val text = forwardText
                forwardText = null
                if (text != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    viewModel.forward(text, uids, forwardTargets.associate { it.profile.uid to it.profile.displayName })
                }
            },
            onDismiss = { forwardText = null },
        )

        // The chat's menu (user's request, 2026-11-10): delete, block, report, like WhatsApp.
        val first = name.substringBefore(' ').ifEmpty { "them" }
        GlassBottomSheet(visible = chatMenu, onDismiss = { chatMenu = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GlassRow("Delete chat", icon = Icons.Filled.Delete, onClick = {
                    chatMenu = false
                    confirmClear = true
                })
                if (!notFriends) {
                    GlassRow("Block $first", icon = ImageVector.vectorResource(R.drawable.ic_block), onClick = {
                        chatMenu = false
                        confirmBlock = true
                    })
                }
                GlassRow("Report $first", icon = ImageVector.vectorResource(R.drawable.ic_flag), onClick = {
                    chatMenu = false
                    reporting = true
                })
            }
        }
        ReportSheet(
            visible = reporting,
            title = "Report $name",
            evidenceNote = "$first's last ${Safety.MAX_EVIDENCE} messages in this chat are sent with your report so it can be reviewed. $first isn't told.",
            blockLabel = "Also block $first",
            busy = safetyBusy,
            onDismiss = { reporting = false },
            onSubmit = { reason, details, block ->
                reporting = false
                viewModel.report(reason, details, block, onBlocked = onBack)
            },
        )
    }
    if (confirmBlock) {
        BlockDialog(
            title = "Block $name?",
            message = "You won't be friends any more. They can't message you, send you requests or see your activity. You can unblock them from Friends.",
            confirm = "Block",
            onConfirm = {
                confirmBlock = false
                viewModel.block(onDone = onBack)
            },
            onDismiss = { confirmBlock = false },
        )
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
