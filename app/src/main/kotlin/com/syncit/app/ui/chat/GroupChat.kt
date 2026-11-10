package com.syncit.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.text.style.TextAlign
import com.syncit.app.ui.glass.GlassRow
import com.syncit.app.ui.glass.GlassSwitchRow
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.R
import com.syncit.app.push.PushNotifications
import com.syncit.app.ui.safety.BlockDialog
import com.syncit.app.ui.safety.ReportSheet
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSmallButton
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.glass.ChipShape
import com.syncit.app.ui.glass.raised
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.ChatRoomLink
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Friend
import com.syncit.core.model.Group
import com.syncit.core.model.UserProfile
import com.syncit.core.social.GroupChats
import com.syncit.core.social.ReportReason
import com.syncit.core.social.Safety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A group chat with its own room (user's request, 2026-11-05): the room stays live while anyone is
 * in it, others join whenever they like, and anyone inside can ping those who haven't joined.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModel(val groupId: String, private val container: AppContainer) : ViewModel() {
    private val social = container.social
    private val groups = social.groups
    val myUid: String? get() = social.auth.currentUid

    data class GroupState(val loaded: Boolean, val group: Group?)

    val group: StateFlow<GroupState> = groups.observeGroup(groupId)
        .map { GroupState(true, it) }
        .catch { emit(GroupState(true, null)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupState(false, null))

    /** Members' profiles (members needn't be our friends). */
    val people: StateFlow<Map<String, UserProfile>> = group
        .map { it.group?.memberUids.orEmpty().sorted() }
        .distinctUntilChanged()
        .flatMapLatest { uids ->
            if (uids.isEmpty()) flowOf(emptyMap())
            else combine(uids.map { uid -> social.profiles.observeProfile(uid).catch { emit(null) } }) { arr ->
                arr.filterNotNull().associateBy { it.uid }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val friends: StateFlow<List<Friend>> = social.friends.observeFriends()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Friends (not yet members) with an unanswered invite here: shown as "Invited", not "Add". */
    val invited: StateFlow<Set<String>> = combine(friends, group) { f, g ->
        val members = g.group?.memberUids.orEmpty().toSet()
        f.map { it.profile.uid }.filter { it !in members }.sorted()
    }
        .distinctUntilChanged()
        .flatMapLatest { uids -> groups.observeInvited(groupId, uids).catch { emit(emptySet()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val messages: StateFlow<List<DirectMessage>?> = groups.observeMessages(groupId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val room: StateFlow<ChatRoomLink?> = groups.observeRoom(groupId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _pinged = MutableStateFlow<Set<String>>(emptySet())
    val pinged: StateFlow<Set<String>> = _pinged.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    fun markRead() {
        viewModelScope.launch { runCatching { groups.markRead(groupId) } }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        action("Couldn't send") { groups.send(groupId, trimmed) }
    }

    fun startRoom(onStarted: (String) -> Unit) = action("Couldn't start the room", busy = true) {
        val name = container.prefs.displayName.trim().ifBlank { "Host" }
        val code = container.sessionController.state.value.roomCode ?: container.transport.createRoom(name)
        container.prefs.lastRoomCode = code
        groups.setRoom(groupId, code)
        container.chatRooms.joined(code, groupId = groupId)
        onStarted(code)
    }

    fun joinRoom(code: String, onJoin: (String) -> Unit) {
        container.chatRooms.joined(code, groupId = groupId)
        onJoin(code)
    }

    fun ping(uid: String) {
        if (uid in _pinged.value) return
        _pinged.update { it + uid }
        action("Couldn't ping") { groups.ping(groupId, uid) }
    }

    fun rename(name: String) {
        val clean = GroupChats.cleanName(name) ?: return
        action("Couldn't rename the group") {
            groups.rename(groupId, clean)
            _notice.value = "Renamed to $clean"
        }
    }

    fun add(uids: Collection<String>) = action("Couldn't add people") {
        groups.addMembers(groupId, uids)
        _notice.value = "Added. Anyone who asked to be asked first gets an invite."
    }

    fun remove(uid: String) = action("Couldn't remove them") { groups.removeMember(groupId, uid) }

    fun setAdmin(uid: String, admin: Boolean) = action(if (admin) "Couldn't make them admin" else "Couldn't dismiss them as admin") {
        groups.setAdmin(groupId, uid, admin)
    }

    fun setSettings(onlyAdminsSend: Boolean, onlyAdminsEdit: Boolean) = action("Couldn't change the group settings") {
        groups.setSettings(groupId, onlyAdminsSend, onlyAdminsEdit)
    }

    fun leave(onLeft: () -> Unit) = action("Couldn't leave the group", busy = true) {
        groups.leave(groupId)
        onLeft()
    }

    /** Leaves the group and stops anyone adding us back or inviting us (user's request, 2026-11-10). */
    fun blockGroup(onLeft: () -> Unit) = action("Couldn't block the group", busy = true) {
        social.safety.blockGroup(groupId, group.value.group?.name.orEmpty())
        onLeft()
    }

    /** Reports the group with its last few messages from others; [block] also leaves and blocks it. */
    fun report(reason: ReportReason, details: String?, block: Boolean, onLeft: () -> Unit) = action("Couldn't send the report", busy = true) {
        val name = group.value.group?.name.orEmpty()
        val others = messages.value.orEmpty().filter { it.fromUid != myUid }
        social.safety.reportGroup(groupId, name, reason, details, others.takeLast(Safety.MAX_EVIDENCE), blocked = block)
        if (block) {
            social.safety.blockGroup(groupId, name)
            onLeft()
        } else {
            _notice.value = "Thanks. Your report was sent."
        }
    }

    private fun action(failure: String, busy: Boolean = false, block: suspend () -> Unit) {
        if (busy && _busy.value) return
        viewModelScope.launch {
            if (busy) _busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = "$failure: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (busy) _busy.value = false
            }
        }
    }
}

@Composable
fun GroupChatScreen(
    viewModel: GroupChatViewModel,
    onBack: () -> Unit,
    onOpenRoom: (code: String) -> Unit,
    onOpenInfo: () -> Unit,
    onAddPeople: () -> Unit,
) {
    val p = Glass.palette
    val state by viewModel.group.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val group = state.group
    val me = viewModel.myUid

    LaunchedEffect(messages?.size) { viewModel.markRead() }
    val context = LocalContext.current
    DisposableEffect(viewModel.groupId) {
        PushNotifications.openGroupId = viewModel.groupId
        PushNotifications.cancel(context, PushNotifications.TYPE_GROUP, viewModel.groupId)
        onDispose { if (PushNotifications.openGroupId == viewModel.groupId) PushNotifications.openGroupId = null }
    }
    // Left the group, or removed: nothing to show.
    LaunchedEffect(state) { if (state.loaded && state.group == null) onBack() }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Avatar(group?.name ?: "?", size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable(onClick = onOpenInfo)) {
                    Text(group?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val live = room
                    Text(
                        group?.let { GroupWording.subtitle(it.members.size, live?.inRoom?.size) }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (live != null) p.success else p.textSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val live = room
                if (live != null) {
                    GlassSmallButton(if (me in live.inRoom) "Open room" else "Join room", onClick = { viewModel.joinRoom(live.code, onOpenRoom) })
                } else {
                    GlassSmallButton(if (busy) "Starting…" else "Start room", onClick = { viewModel.startRoom(onOpenRoom) }, enabled = !busy && group != null)
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Filled.Info, "Group info", onOpenInfo, plain = true)
            }

            // A group of one (created without anyone, or everyone else left): offer Add people.
            if (group != null && group.members.size <= 1) {
                GlassPanel(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    contentPadding = PaddingValues(16.dp),
                    spacing = 12.dp,
                ) {
                    Text("It's just you here", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                    Text(
                        "Add friends to chat and watch together. You can still start a room on your own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                    )
                    if (group.isAdmin(me)) GlassSmallButton("Add people", onClick = onAddPeople)
                }
            }

            room?.let { live ->
                if (me !in live.inRoom) {
                    GlassPanel(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        contentPadding = PaddingValues(16.dp),
                        onClick = { viewModel.joinRoom(live.code, onOpenRoom) },
                    ) {
                        val names = live.inRoom.mapNotNull { people[it]?.displayName?.substringBefore(' ') }
                        Text(
                            "Room live · ${names.joinToString(", ").ifEmpty { "${live.inRoom.size} in" }}",
                            style = MaterialTheme.typography.titleSmall,
                            color = p.textPrimary,
                        )
                        Text("Tap to join. They can see when you're in.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                    }
                }
            }

            val list = messages.orEmpty()
            val listState = rememberLazyListState()
            LaunchedEffect(list.size) { if (list.isNotEmpty()) listState.animateScrollToItem(list.lastIndex) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { m ->
                    val mine = m.fromUid == me
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        if (!mine) {
                            Text(
                                people[m.fromUid]?.displayName ?: "Someone",
                                style = MaterialTheme.typography.labelMedium,
                                color = p.accentText,
                                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                            )
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (mine) p.accent else p.glassFallback)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = if (mine) p.onAccent else p.textPrimary)
                        }
                    }
                }
            }

            if (group != null && !group.canSend(me)) {
                Text(
                    "Only admins can send messages",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.textSecondary,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
                    textAlign = TextAlign.Center,
                )
            } else Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassTextField(
                    value = draft,
                    onValueChange = { draft = it.take(1_000) },
                    placeholder = "Message ${group?.name.orEmpty()}",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    singleLine = false,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.AutoMirrored.Filled.Send, "Send", {
                    viewModel.send(draft)
                    draft = ""
                }, enabled = draft.isNotBlank())
            }
        }
        NoticeBanner(notice, onDismiss = viewModel::dismissNotice, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/**
 * Group info (WhatsApp-style, user's request 2026-11-10): the name (editable unless only admins may),
 * members with their admin tag (tap one for Message / Make admin / Remove), Add people for admins,
 * the group settings for admins, and Leave group. Also opened from the Chats list's long-press.
 */
@Composable
fun GroupInfoScreen(
    viewModel: GroupChatViewModel,
    onBack: () -> Unit,
    onAddPeople: () -> Unit,
    onOpenChat: (uid: String) -> Unit,
    onLeft: () -> Unit,
) {
    val p = Glass.palette
    val state by viewModel.group.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val invited by viewModel.invited.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val pinged by viewModel.pinged.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val group = state.group
    val me = viewModel.myUid
    var editing by rememberSaveable { mutableStateOf(false) }
    var name by remember(group?.name) { mutableStateOf(group?.name.orEmpty()) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var memberFor by remember { mutableStateOf<String?>(null) }
    var lastMember by remember { mutableStateOf<String?>(null) }
    val friendUids = friends.mapTo(HashSet()) { it.profile.uid }

    LaunchedEffect(state) { if (state.loaded && state.group == null) onBack() }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(title = "Group info", navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) })
            if (group == null) return@Column
            val admin = group.isAdmin(me)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(group.name, size = 88.dp)
                    Spacer(Modifier.height(12.dp))
                    if (editing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassTextField(
                                value = name,
                                onValueChange = { name = it.take(GroupChats.MAX_NAME_LENGTH) },
                                placeholder = "Group name",
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            GlassSmallButton(
                                "Save",
                                onClick = {
                                    viewModel.rename(name)
                                    editing = false
                                },
                                enabled = GroupChats.cleanName(name) != null && name.trim() != group.name,
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(group.name, style = MaterialTheme.typography.headlineSmall, color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (group.canEditInfo(me)) {
                                Spacer(Modifier.width(4.dp))
                                GlassIconButton(Icons.Filled.Edit, "Edit group name", { editing = true }, plain = true)
                            }
                        }
                    }
                    Text(
                        "Group · ${group.members.size} ${if (group.members.size == 1) "member" else "members"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                    )
                }

                if (admin) {
                    GlassRow(
                        "Add people",
                        subtitle = if (invited.isEmpty()) null else "${invited.size} invite${if (invited.size == 1) "" else "s"} waiting for an answer",
                        icon = Icons.Filled.Add,
                        onClick = onAddPeople,
                    )
                }

                SectionLabel("${group.members.size} ${if (group.members.size == 1) "member" else "members"}", Modifier.padding(top = 4.dp))
                GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), spacing = 0.dp) {
                    for (m in group.members.sortedWith(compareBy({ it.uid != me }, { !it.admin }, { it.joinedAt }))) {
                        val profile = people[m.uid]
                        val inRoom = room?.inRoom?.contains(m.uid) == true
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = m.uid != me) {
                                    memberFor = m.uid
                                    lastMember = m.uid
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(profile?.displayName ?: "?", size = 40.dp, photoUrl = profile?.photoUrl)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (m.uid == me) "You" else profile?.displayName ?: "Member",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = p.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val line = when {
                                    inRoom -> "In the room"
                                    group.isCreator(m.uid) -> "Created the group"
                                    else -> null
                                }
                                line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (inRoom) p.success else p.textSecondary) }
                            }
                            if (m.admin) {
                                Text(
                                    "Group admin",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = p.textSecondary,
                                    modifier = Modifier
                                        .raised(ChipShape)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                if (admin) {
                    SectionLabel("Group settings", Modifier.padding(top = 4.dp))
                    GlassPanel(Modifier.fillMaxWidth(), spacing = 8.dp) {
                        GlassSwitchRow(
                            title = "Only admins can send messages",
                            subtitle = "Everyone else can still read, join the room and watch.",
                            checked = group.onlyAdminsSend,
                            onCheckedChange = { viewModel.setSettings(onlyAdminsSend = it, onlyAdminsEdit = group.onlyAdminsEdit) },
                        )
                        GlassSwitchRow(
                            title = "Only admins can edit group info",
                            subtitle = "The group's name.",
                            checked = group.onlyAdminsEdit,
                            onCheckedChange = { viewModel.setSettings(onlyAdminsSend = group.onlyAdminsSend, onlyAdminsEdit = it) },
                        )
                    }
                    Text(
                        "Admins can add and remove people, make others admin and change these settings. Nobody can remove the group's creator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                    )
                }

                Spacer(Modifier.height(4.dp))
                GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), spacing = 0.dp) {
                    GlassRow("Exit group", icon = ImageVector.vectorResource(R.drawable.ic_exit), onClick = { confirmLeave = true })
                    GlassRow("Block group", icon = ImageVector.vectorResource(R.drawable.ic_block), onClick = { confirmBlock = true })
                    GlassRow("Report group", icon = ImageVector.vectorResource(R.drawable.ic_flag), onClick = { reporting = true })
                }
            }
        }
        NoticeBanner(notice, onDismiss = viewModel::dismissNotice, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

        val g = group
        lastMember?.let { uid ->
            if (g != null) {
                MemberSheet(
                    visible = memberFor != null,
                    onDismiss = { memberFor = null },
                    name = people[uid]?.displayName ?: "Member",
                    photoUrl = people[uid]?.photoUrl,
                    group = g,
                    me = me,
                    uid = uid,
                    isFriend = uid in friendUids,
                    canPing = room != null && room?.inRoom?.contains(uid) != true && uid !in pinged,
                    onMessage = {
                        memberFor = null
                        onOpenChat(uid)
                    },
                    onPing = {
                        memberFor = null
                        viewModel.ping(uid)
                    },
                    onSetAdmin = { makeAdmin ->
                        memberFor = null
                        viewModel.setAdmin(uid, makeAdmin)
                    },
                    onRemove = {
                        memberFor = null
                        viewModel.remove(uid)
                    },
                )
            }
        }
    }
    ReportSheet(
        visible = reporting,
        title = "Report ${group?.name.orEmpty()}",
        evidenceNote = "The last ${Safety.MAX_EVIDENCE} messages from others in this group are sent with your report so it can be reviewed. Nobody in the group is told.",
        blockLabel = "Also exit and block this group",
        busy = busy,
        onDismiss = { reporting = false },
        onSubmit = { reason, details, block ->
            reporting = false
            viewModel.report(reason, details, block, onLeft)
        },
    )
    if (confirmBlock) {
        BlockDialog(
            title = "Block ${group?.name.orEmpty()}?",
            message = "You'll exit the group, and nobody can add you back or invite you to it. You can unblock it from Friends.",
            confirm = "Exit and block",
            onConfirm = {
                confirmBlock = false
                viewModel.blockGroup(onLeft)
            },
            onDismiss = { confirmBlock = false },
        )
    }
    if (confirmLeave) {
        LeaveGroupDialog(
            name = group?.name.orEmpty(),
            alone = (group?.members?.size ?: 0) <= 1,
            onConfirm = {
                confirmLeave = false
                viewModel.leave(onLeft)
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

/** Tapping a member in Group info: message them, ping them into the room, and the admin actions. */
@Composable
private fun MemberSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    name: String,
    photoUrl: String?,
    group: Group,
    me: String?,
    uid: String,
    isFriend: Boolean,
    canPing: Boolean,
    onMessage: () -> Unit,
    onPing: () -> Unit,
    onSetAdmin: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val p = Glass.palette
    val manage = group.canManage(me, uid)
    val isAdmin = group.isAdmin(uid)
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, size = 40.dp, photoUrl = photoUrl)
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (isFriend) GlassRow("Message $name", icon = Icons.AutoMirrored.Filled.Send, onClick = onMessage)
            if (canPing) GlassRow("Ask to join the room", icon = Icons.Filled.Notifications, onClick = onPing)
            if (manage) {
                GlassRow(
                    if (isAdmin) "Dismiss as admin" else "Make group admin",
                    icon = Icons.Filled.Star,
                    onClick = { onSetAdmin(!isAdmin) },
                )
                GlassRow("Remove from group", icon = Icons.Filled.Delete, onClick = onRemove)
            }
            if (!isFriend && !canPing && !manage) {
                Text(
                    if (group.isCreator(uid)) "Created this group." else "Not on your friends list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )
            }
        }
    }
}

/** Add people (admins): friends not in the group yet; those with an unanswered invite show "Invited". */
@Composable
fun AddGroupMembersScreen(viewModel: GroupChatViewModel, onBack: () -> Unit) {
    val p = Glass.palette
    val state by viewModel.group.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val invited by viewModel.invited.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val group = state.group
    LaunchedEffect(state) { if (state.loaded && state.group == null) onBack() }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(title = "Add people", navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) })
            if (group == null) return@Column
            val addable = friends.filter { it.profile.uid !in group.memberUids }.sortedBy { it.profile.displayName.lowercase() }
            Text(
                "Friends who chose \"Ask me first\" get an invite; the rest are added straight away.",
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (addable.isEmpty()) {
                Text(
                    if (friends.isEmpty()) "Add friends first (+ in Chats), then add them here." else "All your friends are already in this group.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.textSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(addable, key = { it.profile.uid }) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(f.profile.displayName, size = 44.dp, photoUrl = f.profile.photoUrl)
                            Spacer(Modifier.width(12.dp))
                            Text(f.profile.displayName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, modifier = Modifier.weight(1f))
                            if (f.profile.uid in invited) {
                                GlassSmallButton("Invited", onClick = {}, primary = false, enabled = false)
                            } else {
                                GlassSmallButton("Add", onClick = { viewModel.add(listOf(f.profile.uid)) })
                            }
                        }
                    }
                }
            }
        }
        NoticeBanner(notice, onDismiss = viewModel::dismissNotice, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/** "Leave <group>?" confirmation (Group info and the Chats list's long-press menu). */
@Composable
internal fun LeaveGroupDialog(name: String, alone: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = Glass.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave $name?") },
        text = {
            Text(
                if (alone) {
                    "You're the only one in it, so it disappears from your Chats and nobody is left to add you back."
                } else {
                    "You'll stop getting its messages. Someone in the group can add you again."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Leave", color = p.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** New group: a name and the friends to add (user's request, 2026-11-05). */
class NewGroupViewModel(private val container: AppContainer) : ViewModel() {
    val friends: StateFlow<List<Friend>> = container.social.friends.observeFriends()
        .map { list -> list.sortedBy { it.profile.displayName.lowercase() } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismissError() {
        _error.value = null
    }

    fun create(name: String, uids: Set<String>, onCreated: (String) -> Unit) {
        if (_creating.value) return
        _creating.value = true
        viewModelScope.launch {
            try {
                onCreated(container.social.groups.create(name, uids))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't create the group"
            } finally {
                _creating.value = false
            }
        }
    }
}

@Composable
fun NewGroupScreen(viewModel: NewGroupViewModel, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val p = Glass.palette
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(title = "New group", navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) })
            GlassTextField(
                value = name,
                onValueChange = { name = it.take(GroupChats.MAX_NAME_LENGTH) },
                placeholder = "Group name, like Movie Night",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                "Pick friends now or add them later. People who chose \"Ask me first\" get an invite; the rest are added straight away.",
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(friends, key = { it.profile.uid }) { f ->
                    val on = f.profile.uid in picked
                    GlassPanel(
                        Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        onClick = { picked = if (on) picked - f.profile.uid else picked + f.profile.uid },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(f.profile.displayName, size = 42.dp, photoUrl = f.profile.photoUrl)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.profile.displayName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                                Text("@${f.profile.username}", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                            }
                            Box(
                                Modifier.size(24.dp).clip(CircleShape).background(if (on) p.accent else p.glassFallback),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) androidx.compose.material3.Icon(Icons.Filled.Check, contentDescription = "Selected", tint = p.onAccent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            GlassButton(
                text = GroupWording.createLabel(picked.size),
                onClick = { viewModel.create(name, picked, onCreated) },
                enabled = GroupWording.canCreate(name, creating),
                loading = creating,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            )
        }
        NoticeBanner(error, onDismiss = viewModel::dismissError, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
