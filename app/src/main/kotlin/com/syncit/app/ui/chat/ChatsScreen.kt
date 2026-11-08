package com.syncit.app.ui.chat

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.social.WatchTogether
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassRow
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSmallButton
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.home.FriendSheet
import com.syncit.app.ui.home.HomeViewModel
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Friend
import com.syncit.core.model.Group
import com.syncit.core.model.GroupInvite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/** One 1:1 conversation in the Chats list. */
data class ChatRow(val friend: Friend, val last: DirectMessage, val unread: Int) {
    val uid: String get() = friend.profile.uid
}

/** One group in the Chats list; [last] null = no messages yet; [inRoom] = people in its room now. */
data class GroupRow(val group: Group, val last: DirectMessage?, val unread: Int, val inRoom: Int)

/** Chats tab entries: 1:1 chats and groups together, newest first. */
sealed interface ChatEntry {
    val key: String
    val sortAt: Long

    data class Dm(val row: ChatRow) : ChatEntry {
        override val key get() = "dm:${row.uid}"
        override val sortAt get() = row.last.sentAt.takeIf { it > 0 } ?: Long.MAX_VALUE
    }

    data class Grp(val row: GroupRow) : ChatEntry {
        override val key get() = "group:${row.group.id}"
        override val sortAt get() = row.last?.sentAt ?: row.group.createdAt
    }
}

/** Chats tab: every conversation (1:1 and groups), newest first (WhatsApp-style), plus group invites. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModel(container: AppContainer) : ViewModel() {
    private val social = container.social
    private val groups = social.groups
    val myUid: String? get() = social.auth.currentUid

    private val friends = social.friends.observeFriends()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChange(value: String) {
        _query.value = value.take(MAX_QUERY)
    }

    /** Friends whose name or username contains the search (all friends, not only past chats). */
    val matches: StateFlow<List<Friend>> = combine(friends, _query) { list, q -> matchFriends(list.orEmpty(), q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val dmRows: Flow<List<ChatRow>> = combine(
        friends.filterNotNull(),
        social.dms.observeLatest().catch { emit(emptyMap()) },
        social.dms.observeUnreadCounts().catch { emit(emptyMap()) },
    ) { friends, latest, unread ->
        friends.mapNotNull { f -> latest[f.profile.uid]?.let { ChatRow(f, it, unread[f.profile.uid] ?: 0) } }
    }

    private val myGroups = groups.observeMyGroups().catch { emit(emptyList()) }

    /** Group id → how many are in its room right now. */
    private val roomCounts: Flow<Map<String, Int>> = myGroups
        .map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id -> groups.observeRoom(id).catch { emit(null) }.map { id to (it?.inRoom?.size ?: 0) } }) { arr -> arr.toMap() }
        }

    private val groupRows: Flow<List<GroupRow>> = combine(
        myGroups,
        groups.observeLatest().catch { emit(emptyMap()) },
        groups.observeUnreadCounts().catch { emit(emptyMap()) },
        roomCounts,
    ) { list, latest, unread, rooms ->
        list.map { g -> GroupRow(g, latest[g.id], unread[g.id] ?: 0, rooms[g.id] ?: 0) }
    }

    /** Null while loading. */
    val rows: StateFlow<List<ChatEntry>?> = combine(dmRows, flowOf(Unit).flatMapLatest { groupRows }) { dms, grps ->
        (dms.map { ChatEntry.Dm(it) } + grps.map { ChatEntry.Grp(it) }).sortedByDescending { it.sortAt }
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val invites: StateFlow<List<GroupInvite>> = groups.observeInvites()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    /** "Delete chat" for us only; it comes back if they write again. */
    fun deleteChat(friendUid: String) = act("Couldn't delete the chat") { social.dms.clearChat(friendUid) }

    /** Leave a group from the list's long-press menu (same as the group info sheet's Leave group). */
    fun leaveGroup(groupId: String) = act("Couldn't leave the group") { groups.leave(groupId) }

    fun accept(invite: GroupInvite, onJoined: (String) -> Unit) = act("Couldn't join ${invite.groupName}") {
        groups.acceptInvite(invite.groupId)
        onJoined(invite.groupId)
    }

    fun decline(invite: GroupInvite) = act("Couldn't decline") { groups.declineInvite(invite.groupId) }

    private fun act(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = failure
            }
        }
    }

    private companion object {
        const val MAX_QUERY = 40
    }
}

/** Case-insensitive match on display name or username ("@" ignored); names starting with it first. */
internal fun matchFriends(friends: List<Friend>, query: String): List<Friend> {
    val q = query.trim().removePrefix("@").lowercase()
    if (q.isEmpty()) return emptyList()
    return friends
        .filter { f -> f.profile.displayName.lowercase().contains(q) || f.profile.username.lowercase().contains(q) }
        .sortedWith(
            compareBy<Friend>(
                { f -> !(f.profile.displayName.lowercase().startsWith(q) || f.profile.username.lowercase().startsWith(q)) },
                { f -> f.profile.displayName.lowercase() },
            ),
        )
}

/**
 * [home] is the activity-scoped Home view model: long-press -> View profile opens its friend sheet.
 * [groupInfoSheet] draws the group info sheet for a group id (long-press -> Group info).
 */
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    home: HomeViewModel,
    onOpenChat: (String) -> Unit,
    onOpenGroup: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onStartRoomWith: (code: String, friendUid: String) -> Unit = { _, _ -> },
    groupInfoSheet: @Composable (groupId: String, visible: Boolean, onDismiss: () -> Unit) -> Unit = { _, _, _ -> },
) {
    val p = Glass.palette
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val invites by viewModel.invites.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val bottom = LocalBottomBarInset.current
    val homeUi by home.ui.collectAsStateWithLifecycle()
    var deleteFor by remember { mutableStateOf<ChatRow?>(null) }
    var leaveFor by remember { mutableStateOf<GroupRow?>(null) }
    var profileUid by remember { mutableStateOf<String?>(null) }
    // Long-press menu and the group info sheet; each keeps its last entry while it slides away.
    var menuFor by remember { mutableStateOf<ChatEntry?>(null) }
    var lastMenu by remember { mutableStateOf<ChatEntry?>(null) }
    var infoFor by remember { mutableStateOf<String?>(null) }
    var lastInfo by remember { mutableStateOf<String?>(null) }
    val openMenu = { entry: ChatEntry ->
        menuFor = entry
        lastMenu = entry
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(title = "Chats", actions = { GlassSmallButton("New group", onClick = onNewGroup, primary = false) })
            GlassTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = "Search friends",
                leadingIcon = Icons.Filled.Search,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                trailing = if (query.isNotEmpty()) {
                    { GlassIconButton(Icons.Filled.Close, "Clear search", { viewModel.onQueryChange("") }, plain = true) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp),
            )
            val list = rows
            when {
                query.isNotBlank() -> SearchResults(matches, bottom, onOpenChat)

                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.accent)
                }

                list.isEmpty() && invites.isEmpty() -> Box(
                    Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = bottom).padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No chats yet. Message a friend, or tap New group to start one with several friends.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottom),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(invites, key = { "invite:${it.groupId}" }) { invite ->
                        InviteCard(invite, onJoin = { viewModel.accept(invite, onOpenGroup) }, onDecline = { viewModel.decline(invite) })
                    }
                    items(list, key = { it.key }) { entry ->
                        when (entry) {
                            is ChatEntry.Dm -> ChatRowView(
                                entry.row,
                                viewModel.myUid,
                                onClick = { onOpenChat(entry.row.uid) },
                                onLongClick = { openMenu(entry) },
                            )
                            is ChatEntry.Grp -> GroupRowView(
                                entry.row,
                                viewModel.myUid,
                                onClick = { onOpenGroup(entry.row.group.id) },
                                onLongClick = { openMenu(entry) },
                            )
                        }
                    }
                }
            }
        }
        // Our own notices first, then the friend sheet's (Ask to join, Start a room...).
        val shown = notice ?: homeUi.message
        NoticeBanner(
            shown,
            onDismiss = { if (notice != null) viewModel.dismissNotice() else home.dismissMessage() },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        lastMenu?.let { entry ->
            ChatRowMenuSheet(
                entry = entry,
                visible = menuFor != null,
                onDismiss = { menuFor = null },
                onAction = { action ->
                    menuFor = null
                    when (action) {
                        ChatRowAction.VIEW_PROFILE -> (entry as? ChatEntry.Dm)?.let { profileUid = it.row.uid }
                        ChatRowAction.DELETE_CHAT -> (entry as? ChatEntry.Dm)?.let { deleteFor = it.row }
                        ChatRowAction.GROUP_INFO -> (entry as? ChatEntry.Grp)?.let {
                            infoFor = it.row.group.id
                            lastInfo = it.row.group.id
                        }
                        ChatRowAction.LEAVE_GROUP -> (entry as? ChatEntry.Grp)?.let { leaveFor = it.row }
                    }
                },
            )
        }

        FriendSheet(
            viewModel = home,
            uid = profileUid,
            onDismiss = { profileUid = null },
            onOpenChat = onOpenChat,
            onStartRoomWith = onStartRoomWith,
        )

        lastInfo?.let { id -> groupInfoSheet(id, infoFor != null) { infoFor = null } }
    }

    leaveFor?.let { row ->
        LeaveGroupDialog(
            name = row.group.name,
            alone = row.group.members.size <= 1,
            onConfirm = {
                leaveFor = null
                viewModel.leaveGroup(row.group.id)
            },
            onDismiss = { leaveFor = null },
        )
    }

    deleteFor?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete chat with ${row.friend.profile.displayName}?") },
            text = { Text("It's removed from your phone and your Chats list. They still have their copy.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    viewModel.deleteChat(row.uid)
                }) { Text("Delete chat", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
        )
    }
}

/** Long-press menu for a Chats row, styled like the message long-press menu (MessageActionsSheet). */
@Composable
private fun ChatRowMenuSheet(entry: ChatEntry, visible: Boolean, onDismiss: () -> Unit, onAction: (ChatRowAction) -> Unit) {
    val p = Glass.palette
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (entry) {
                is ChatEntry.Dm -> Avatar(entry.row.friend.profile.displayName, size = 40.dp, photoUrl = entry.row.friend.profile.photoUrl)
                is ChatEntry.Grp -> Avatar(entry.row.group.name, size = 40.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                when (entry) {
                    is ChatEntry.Dm -> entry.row.friend.profile.displayName
                    is ChatEntry.Grp -> entry.row.group.name
                },
                style = MaterialTheme.typography.titleMedium,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (action in chatRowActions(entry)) {
                GlassRow(
                    action.label,
                    icon = when (action) {
                        ChatRowAction.VIEW_PROFILE -> Icons.Filled.Person
                        ChatRowAction.DELETE_CHAT -> Icons.Filled.Delete
                        ChatRowAction.GROUP_INFO -> Icons.Filled.Info
                        ChatRowAction.LEAVE_GROUP -> Icons.AutoMirrored.Filled.ExitToApp
                    },
                    onClick = { onAction(action) },
                )
            }
        }
    }
}

@Composable
private fun InviteCard(invite: GroupInvite, onJoin: () -> Unit, onDecline: () -> Unit) {
    val p = Glass.palette
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp), spacing = 10.dp) {
        Text(
            "${invite.fromName.ifBlank { "A friend" }} invited you to ${invite.groupName}",
            style = MaterialTheme.typography.titleSmall,
            color = p.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassSmallButton("Join", onClick = onJoin)
            GlassSmallButton("Decline", onClick = onDecline, primary = false)
        }
    }
}

/** Friends matching the search; tap to open (or start) the chat. */
@Composable
private fun SearchResults(matches: List<Friend>, bottom: Dp, onOpenChat: (String) -> Unit) {
    val p = Glass.palette
    if (matches.isEmpty()) {
        Text(
            "No friends match that",
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(matches, key = { it.profile.uid }) { friend ->
            GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), onClick = { onOpenChat(friend.profile.uid) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        friend.profile.displayName,
                        size = 44.dp,
                        online = friend.presence?.online == true,
                        photoUrl = friend.profile.photoUrl,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            friend.profile.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = p.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("@${friend.profile.username}", style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun timeLabel(sentAt: Long): String {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val day = ChatTimes.dayLabel(sentAt, now, zone)
    return if (day == "Today") ChatTimes.timeLabel(sentAt, now, zone, DateFormat.is24HourFormat(context)) else day
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRowView(row: ChatRow, myUid: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = Glass.palette
    val haptics = LocalHapticFeedback.current
    val time = timeLabel(row.last.sentAt)
    val unread = row.unread > 0

    GlassPanel(
        Modifier
            .fillMaxWidth()
            .rowClicks(onClick, onLongClick, haptics),
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                row.friend.profile.displayName,
                size = 48.dp,
                online = row.friend.presence?.online == true,
                photoUrl = row.friend.profile.photoUrl,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.friend.profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(time, style = MaterialTheme.typography.labelSmall, color = if (unread) p.accentText else p.textSecondary)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val last = row.last
                    val watched = last.watched
                    Text(
                        when {
                            watched != null -> WatchTogether.headline(watched)
                            last.deleted -> if (last.fromUid == myUid) "You deleted this message" else "This message was deleted"
                            last.fromUid == myUid -> "You: ${last.text}"
                            else -> last.text
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = if (last.deleted) FontStyle.Italic else FontStyle.Normal,
                            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (unread) p.textPrimary else p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unread) UnreadBadge(row.unread)
                }
            }
        }
    }
}

/** Tap opens the chat; long-press (haptic tick) opens the row menu. TalkBack offers it as "More options". */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.rowClicks(onClick: () -> Unit, onLongClick: () -> Unit, haptics: HapticFeedback): Modifier =
    clip(RoundedCornerShape(24.dp)).combinedClickable(
        onClickLabel = "Open chat",
        onLongClickLabel = "More options",
        onClick = onClick,
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
        },
    )

@Composable
private fun GroupRowView(row: GroupRow, myUid: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = Glass.palette
    val haptics = LocalHapticFeedback.current
    val unread = row.unread > 0
    GlassPanel(Modifier.fillMaxWidth().rowClicks(onClick, onLongClick, haptics), contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(row.group.name, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    row.last?.let { Text(timeLabel(it.sentAt), style = MaterialTheme.typography.labelSmall, color = if (unread) p.accentText else p.textSecondary) }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.inRoom > 0) {
                        Box(Modifier.clip(CircleShape).background(p.success).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Room live · ${row.inRoom}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = p.base)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    val last = row.last
                    Text(
                        when {
                            last == null -> "${row.group.members.size} members"
                            last.fromUid == myUid -> "You: ${last.text}"
                            else -> last.text
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (unread) p.textPrimary else p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unread) UnreadBadge(row.unread)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val p = Glass.palette
    Spacer(Modifier.width(8.dp))
    Box(
        Modifier.clip(CircleShape).background(p.accent).padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = p.onAccent,
        )
    }
}
