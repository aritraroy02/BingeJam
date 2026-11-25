package com.syncit.app.ui.chat

import com.syncit.app.ui.social.ActivityThumb
import com.syncit.app.ui.glass.GlassChip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.syncit.app.R
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
import com.syncit.app.ui.glass.SyncDots
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

/** "Watching now" at the top of Chats: a friend playing something, or a group's live room. */
sealed interface WatchingCard {
    val key: String

    data class FriendWatching(val friend: Friend, val activity: com.syncit.core.model.Activity) : WatchingCard {
        override val key get() = "f:${friend.profile.uid}"
    }

    data class GroupLive(val group: Group, val inRoom: Int) : WatchingCard {
        override val key get() = "g:${group.id}"
    }
}

/** Chats filter chips (2026-11-25 redesign). */
enum class ChatFilter(val label: String) { ALL("All"), UNREAD("Unread"), GROUPS("Groups"), LIVE("Live") }

internal fun ChatEntry.matches(filter: ChatFilter): Boolean = when (filter) {
    ChatFilter.ALL -> true
    ChatFilter.UNREAD -> when (this) {
        is ChatEntry.Dm -> row.unread > 0
        is ChatEntry.Grp -> row.unread > 0
    }
    ChatFilter.GROUPS -> this is ChatEntry.Grp
    ChatFilter.LIVE -> this is ChatEntry.Grp && row.inRoom > 0
}

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

    private val _filter = MutableStateFlow(ChatFilter.ALL)
    val filter: StateFlow<ChatFilter> = _filter.asStateFlow()

    fun setFilter(value: ChatFilter) {
        _filter.value = value
    }

    /** The rows the chips let through. */
    val shown: StateFlow<List<ChatEntry>?> = combine(rows, _filter) { list, f -> list?.filter { it.matches(f) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Friends playing something right now (online), then groups with a live room. */
    val watching: StateFlow<List<WatchingCard>> = combine(friends, myGroups, roomCounts) { list, grps, rooms ->
        val playing = list.orEmpty().mapNotNull { f ->
            com.syncit.app.social.ActivityText.visibleActivity(f.presence)?.let { WatchingCard.FriendWatching(f, it) }
        }.sortedByDescending { it.activity.startedAt }
        val live = grps.mapNotNull { g -> rooms[g.id]?.takeIf { it > 0 }?.let { WatchingCard.GroupLive(g, it) } }
        live + playing
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Groups whose name contains the search. */
    val groupMatches: StateFlow<List<Group>> = combine(myGroups, _query) { list, q ->
        val t = q.trim().lowercase()
        if (t.isEmpty()) emptyList() else list.filter { it.name.lowercase().contains(t) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
 * [onGroupInfo] opens a group's info screen (long-press -> Group info).
 */
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    home: HomeViewModel,
    onOpenChat: (String) -> Unit,
    onOpenGroup: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onAddFriends: () -> Unit = {},
    friendRequests: Int = 0,
    onStartRoomWith: (code: String, friendUid: String) -> Unit = { _, _ -> },
    onGroupInfo: (groupId: String) -> Unit = {},
    onInvite: () -> Unit = {},
    /** The profile page; without it, long-press "View profile" opens the friend sheet. */
    onViewProfile: ((uid: String) -> Unit)? = null,
) {
    val p = Glass.palette
    val rows by viewModel.shown.collectAsStateWithLifecycle()
    val allRows by viewModel.rows.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val watching by viewModel.watching.collectAsStateWithLifecycle()
    val groupMatches by viewModel.groupMatches.collectAsStateWithLifecycle()
    val invites by viewModel.invites.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val bottom = LocalBottomBarInset.current
    val homeUi by home.ui.collectAsStateWithLifecycle()
    var deleteFor by remember { mutableStateOf<ChatRow?>(null) }
    var leaveFor by remember { mutableStateOf<GroupRow?>(null) }
    var profileUid by remember { mutableStateOf<String?>(null) }
    // Long-press menu; it keeps its last entry while it slides away.
    var menuFor by remember { mutableStateOf<ChatEntry?>(null) }
    var lastMenu by remember { mutableStateOf<ChatEntry?>(null) }
    var plusOpen by remember { mutableStateOf(false) }
    val openMenu = { entry: ChatEntry ->
        menuFor = entry
        lastMenu = entry
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(title = "Chats", actions = {
                Box {
                    GlassIconButton(
                        ImageVector.vectorResource(R.drawable.ic_tab_add_friend),
                        if (friendRequests > 0) "Add friends, $friendRequests friend requests" else "Add friends",
                        onAddFriends,
                    )
                    if (friendRequests > 0) {
                        Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(p.danger))
                    }
                }
            })
            GlassTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = "Search chats and friends",
                leadingIcon = Icons.Filled.Search,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                trailing = if (query.isNotEmpty()) {
                    { GlassIconButton(Icons.Filled.Close, "Clear search", { viewModel.onQueryChange("") }, plain = true) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
            )
            val list = rows
            when {
                query.isNotBlank() -> SearchResults(matches, groupMatches, bottom, onOpenChat, onOpenGroup, onAddFriends)

                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.accent)
                }

                allRows?.isEmpty() == true && invites.isEmpty() && watching.isEmpty() -> FirstChat(
                    bottom = bottom,
                    onInvite = onInvite,
                    onAddFriends = onAddFriends,
                    onNewGroup = onNewGroup,
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp + bottom),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // What friends are watching right now: tap a friend for their profile (Ask to join), a group to open it.
                    if (watching.isNotEmpty()) {
                        item(key = "watching") {
                            WatchingStrip(
                                watching,
                                onFriend = { uid -> if (onViewProfile != null) onViewProfile(uid) else profileUid = uid },
                                onGroup = onOpenGroup,
                            )
                        }
                    }
                    item(key = "filters") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (f in ChatFilter.entries) GlassChip(f.label, onClick = { viewModel.setFilter(f) }, selected = filter == f)
                        }
                    }
                    if (list.isEmpty()) {
                        item(key = "none") {
                            Text(
                                when (filter) {
                                    ChatFilter.UNREAD -> "You're all caught up"
                                    ChatFilter.GROUPS -> "No groups yet"
                                    ChatFilter.LIVE -> "No rooms live right now"
                                    ChatFilter.ALL -> "No chats yet"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = p.textSecondary,
                                modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
                            )
                        }
                    }
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
        // "New chat" in thumb reach: Add friends, Invite link, New group.
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = bottom + 16.dp)
                .shadow(6.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(p.textPrimary)
                .clickable(role = Role.Button, onClickLabel = "New chat") { plusOpen = true }
                .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Edit, null, tint = p.base, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("New chat", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = p.base)
        }
        // Our own notices first, then the friend sheet's (Ask to join, Start a room...).
        val shown = notice ?: homeUi.message
        NoticeBanner(
            shown,
            onDismiss = { if (notice != null) viewModel.dismissNotice() else home.dismissMessage() },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        GlassBottomSheet(visible = plusOpen, onDismiss = { plusOpen = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GlassRow(
                    "Add friends",
                    subtitle = when (friendRequests) {
                        0 -> "Find someone by their exact username"
                        1 -> "1 friend request waiting"
                        else -> "$friendRequests friend requests waiting"
                    },
                    icon = ImageVector.vectorResource(R.drawable.ic_tab_add_friend),
                    onClick = {
                        plusOpen = false
                        onAddFriends()
                    },
                )
                GlassRow(
                    "Invite with link or QR",
                    subtitle = "Whoever opens it becomes your friend",
                    icon = ImageVector.vectorResource(R.drawable.ic_qr_code),
                    onClick = {
                        plusOpen = false
                        onInvite()
                    },
                )
                GlassRow(
                    "New group",
                    subtitle = "Chat and watch with several friends",
                    icon = Icons.Filled.Add,
                    onClick = {
                        plusOpen = false
                        onNewGroup()
                    },
                )
            }
        }

        lastMenu?.let { entry ->
            ChatRowMenuSheet(
                entry = entry,
                visible = menuFor != null,
                onDismiss = { menuFor = null },
                onAction = { action ->
                    menuFor = null
                    when (action) {
                        ChatRowAction.VIEW_PROFILE -> (entry as? ChatEntry.Dm)?.let { e ->
                            if (onViewProfile != null) onViewProfile(e.row.uid) else profileUid = e.row.uid
                        }
                        ChatRowAction.DELETE_CHAT -> (entry as? ChatEntry.Dm)?.let { deleteFor = it.row }
                        ChatRowAction.GROUP_INFO -> (entry as? ChatEntry.Grp)?.let { onGroupInfo(it.row.group.id) }
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
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(invite.groupName, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${invite.groupName} invited you", style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("From ${invite.fromName.ifBlank { "a friend" }}", style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 1)
            }
            GlassSmallButton("Join", onClick = onJoin)
            GlassIconButton(Icons.Filled.Close, "Decline", onDecline, plain = true)
        }
    }
}

/** Friends playing something and live group rooms, as small poster cards (the chat's main moment). */
@Composable
private fun WatchingStrip(cards: List<WatchingCard>, onFriend: (String) -> Unit, onGroup: (String) -> Unit) {
    val p = Glass.palette
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Watching now", style = MaterialTheme.typography.labelLarge, color = p.textSecondary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { it.key }) { card ->
                Column(
                    Modifier
                        .width(136.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(p.surface)
                        .border(1.dp, p.hairline, RoundedCornerShape(16.dp))
                        .clickable {
                            when (card) {
                                is WatchingCard.FriendWatching -> onFriend(card.friend.profile.uid)
                                is WatchingCard.GroupLive -> onGroup(card.group.id)
                            }
                        },
                ) {
                    when (card) {
                        is WatchingCard.FriendWatching -> {
                            Box(Modifier.fillMaxWidth().height(76.dp)) {
                                ActivityThumb(card.activity, modifier = Modifier.fillMaxSize(), size = 136.dp)
                                Text(
                                    card.activity.app.displayName,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(card.friend.profile.displayName, size = 24.dp, photoUrl = card.friend.profile.photoUrl)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(card.friend.profile.displayName.substringBefore(' '), style = MaterialTheme.typography.labelMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(card.activity.title ?: card.activity.app.displayName, style = MaterialTheme.typography.labelSmall, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        is WatchingCard.GroupLive -> {
                            Box(Modifier.fillMaxWidth().height(76.dp).background(p.raised), contentAlignment = Alignment.Center) {
                                SyncDots(size = 10.dp)
                            }
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(card.group.name, size = 24.dp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(card.group.name, style = MaterialTheme.typography.labelMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Room live · ${card.inRoom} in", style = MaterialTheme.typography.labelSmall, color = p.success, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** No chats yet: what chats are for, and the three ways in. */
@Composable
private fun FirstChat(bottom: Dp, onInvite: () -> Unit, onAddFriends: () -> Unit, onNewGroup: () -> Unit) {
    val p = Glass.palette
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = bottom).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 100.dp, height = 64.dp).clip(RoundedCornerShape(12.dp)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { SyncDots(size = 10.dp) }
        Spacer(Modifier.height(20.dp))
        Text("Watch with your first friend", style = MaterialTheme.typography.titleLarge, color = p.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Chats are where watch parties start. Add a friend, then press play together.",
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp), spacing = 0.dp) {
            GlassRow("Send your invite link", subtitle = "Whoever opens it becomes your friend", icon = ImageVector.vectorResource(R.drawable.ic_qr_code), onClick = onInvite)
            GlassRow("Find by username", subtitle = "Their exact @username", icon = Icons.Filled.Search, onClick = onAddFriends)
            GlassRow("Start a group", subtitle = "Chat and watch with several friends", icon = Icons.Filled.Add, onClick = onNewGroup)
        }
    }
}

/** Friends and groups matching the search; nothing matches → find someone by username. */
@Composable
private fun SearchResults(
    matches: List<Friend>,
    groups: List<Group>,
    bottom: Dp,
    onOpenChat: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onAddFriends: () -> Unit,
) {
    val p = Glass.palette
    LazyColumn(
        Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (matches.isNotEmpty()) item(key = "friends-label") { SearchLabel("Friends") }
        items(matches, key = { "f:${it.profile.uid}" }) { friend ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenChat(friend.profile.uid) }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(friend.profile.displayName, size = 44.dp, online = friend.presence?.online == true, photoUrl = friend.profile.photoUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(friend.profile.displayName, style = MaterialTheme.typography.titleMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull("@${friend.profile.username}", com.syncit.app.social.ActivityText.visibleActivity(friend.presence)?.let { com.syncit.app.social.ActivityText.activityLine(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlassSmallButton("Message", onClick = { onOpenChat(friend.profile.uid) }, primary = false)
            }
        }
        if (groups.isNotEmpty()) item(key = "groups-label") { SearchLabel("Groups") }
        items(groups, key = { "g:${it.id}" }) { group ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenGroup(group.id) }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(group.name, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.members.size} members", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                }
            }
        }
        item(key = "find") {
            SearchLabel("Not a friend yet?")
            GlassRow("Find by username", subtitle = "Send a friend request", icon = Icons.Filled.Search, onClick = onAddFriends)
        }
    }
}

@Composable
private fun SearchLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Glass.palette.textSecondary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
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

    Column(Modifier.fillMaxWidth().rowClicks(onClick, onLongClick, haptics).padding(horizontal = 4.dp, vertical = 8.dp)) {
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
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium),
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
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal),
                        color = when {
                            last.deleted -> p.textTertiary
                            unread -> p.textPrimary
                            else -> p.textSecondary
                        },
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
    clip(RoundedCornerShape(12.dp)).combinedClickable(
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
    Column(Modifier.fillMaxWidth().rowClicks(onClick, onLongClick, haptics).padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(row.group.name, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.group.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium),
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
                        SyncDots()
                        Spacer(Modifier.width(8.dp))
                        Text("Room live · ${row.inRoom} in", style = MaterialTheme.typography.labelMedium, color = p.textPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    val last = row.last
                    Text(
                        when {
                            last == null -> "${row.group.members.size} members"
                            last.fromUid == myUid -> "You: ${last.text}"
                            else -> last.text
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal),
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
        Modifier
            .clip(CircleShape)
            .background(p.accent)
            .semantics { contentDescription = if (count == 1) "1 unread message" else "$count unread messages" }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = p.onAccent,
        )
    }
}
