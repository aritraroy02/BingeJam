package com.syncit.app.ui.home

import com.syncit.core.model.StreamingService
import com.syncit.core.lookup.TrendingTitle
import com.syncit.core.lookup.TrendingRow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.data.UpdateChecker
import com.syncit.app.di.AppContainer
import com.syncit.app.social.ActivityText
import com.syncit.app.social.RoomWording
import com.syncit.core.config.RoomLinks
import com.syncit.core.model.Activity
import com.syncit.core.model.Friend
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.JoinStatus
import com.syncit.core.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One friend in the Home list. */
data class FriendRow(
    val friend: Friend,
    val online: Boolean,
    /** "Watching S2:E4 · … on Netflix", "Online", "Last seen 5m ago". */
    val status: String,
    /** Shown only while they're online. */
    val activity: Activity?,
    val unread: Int,
    /** We already asked to join what they're playing and they haven't answered. */
    val joinRequested: Boolean,
) {
    val uid: String get() = friend.profile.uid

    /** What they're playing is a jam app (music, YouTube) rather than a watch party. */
    val isJam: Boolean get() = RoomWording.isJam(activity?.app)

    /** "Ask to jam" / "Ask to join" (or "Asked …"), null when there's nothing to join. */
    val askLabel: String? get() = activity?.takeIf { it.app.joinable }?.let { RoomWording.askLabel(isJam, joinRequested) }
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val social = container.social

    data class UiState(
        val code: String = "",
        val showCodeField: Boolean = false,
        val busy: Boolean = false,
        val lastRoom: String? = null,
        val message: String? = null,
    ) {
        val codeValid: Boolean get() = code.length == RoomLinks.CODE_LENGTH
    }

    private val _ui = MutableStateFlow(UiState(lastRoom = container.prefs.lastRoomCode))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val myProfile: StateFlow<UserProfile?> = social.profiles.observeMyProfile()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Server time, refreshed every 30 s so "Last seen 5m ago" stays current. */
    private val now = flow {
        while (true) {
            emit(container.transport.serverClock.serverNowMs())
            delay(30_000)
        }
    }

    val friends: StateFlow<List<FriendRow>?> = combine(
        social.friends.observeFriends(),
        social.dms.observeUnreadCounts().catch { emit(emptyMap()) },
        social.watchInvites.observeMyJoinRequests().catch { emit(emptyList()) },
        now,
    ) { friends, unread, myRequests, nowMs -> buildRows(friends, unread, myRequests, nowMs) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val incomingRequestCount: StateFlow<Int> = social.friends.observeIncomingRequests()
        .map { it.size }
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _update = MutableStateFlow<UpdateChecker.Update?>(null)

    /** A newer release on GitHub, if any (APK installs don't update themselves). */
    val update: StateFlow<UpdateChecker.Update?> = _update.asStateFlow()

    init {
        viewModelScope.launch { _update.value = UpdateChecker.check() }
    }

    /** Upper-cases and drops characters that can't appear in a code. */
    fun onCodeChange(value: String) = _ui.update {
        it.copy(code = value.uppercase().filter { c -> c in RoomLinks.CODE_ALPHABET }.take(RoomLinks.CODE_LENGTH))
    }

    fun toggleCodeField() = _ui.update { it.copy(showCodeField = !it.showCodeField) }

    fun refreshLastRoom() = _ui.update { it.copy(lastRoom = container.prefs.lastRoomCode) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    fun create(onJoined: (String) -> Unit) {
        if (_ui.value.busy) return
        val name = container.prefs.displayName.trim().ifBlank { myProfile.value?.displayName.orEmpty() }
        launchAction("Couldn't create a room", busy = true) {
            val code = container.transport.createRoom(name.ifBlank { "Host" })
            container.prefs.lastRoomCode = code
            onJoined(code)
        }
    }

    /** Creates a room (or uses the one we're syncing in) and invites [row] into it. */
    fun startRoomWith(row: FriendRow, onStarted: (code: String, friendUid: String) -> Unit) {
        if (_ui.value.busy) return
        val name = container.prefs.displayName.trim().ifBlank { myProfile.value?.displayName.orEmpty() }
        launchAction("Couldn't invite ${row.friend.profile.displayName}", busy = true) {
            val code = container.sessionController.state.value.roomCode
                ?: container.transport.createRoom(name.ifBlank { "Host" })
            container.prefs.lastRoomCode = code
            social.watchInvites.inviteToRoom(row.uid, code, null, null)
            onStarted(code, row.uid)
        }
    }

    /** Trending titles per platform (TMDB, through the relay); null while loading, empty if unavailable. */
    val trending: StateFlow<List<TrendingRow>?> = flow { emit(container.trending.trending()) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Looks up [title]'s link in [service] (null = not found) and hands it to [open]. */
    fun watchAlone(title: TrendingTitle, service: StreamingService, open: (String?) -> Unit) {
        if (_ui.value.busy) return
        launchAction("Couldn't open ${title.title}", busy = true) {
            open(container.trending.watchLink(title, service))
        }
    }

    /**
     * Starts a room for [title] (or uses the one we're syncing in) with its link as the room's title,
     * so everyone's "Play in <app>" opens it; then [onStarted] opens the room on "Invite friends".
     */
    fun watchWithFriends(title: TrendingTitle, service: StreamingService, onStarted: (code: String) -> Unit) {
        if (_ui.value.busy) return
        val name = container.prefs.displayName.trim().ifBlank { myProfile.value?.displayName.orEmpty() }
        launchAction("Couldn't start a room", busy = true) {
            val link = container.trending.watchLink(title, service)
            val code = container.sessionController.state.value.roomCode
                ?: container.transport.createRoom(name.ifBlank { "Host" })
            container.prefs.lastRoomCode = code
            if (link != null) container.transport.setTitle(code, service, link)
            onStarted(code)
        }
    }

    fun askToJoin(row: FriendRow) {
        val activity = row.activity ?: return
        launchAction("Couldn't ask ${row.friend.profile.displayName}") {
            social.watchInvites.requestToJoin(row.uid, activity.app, activity.title)
            _ui.update { it.copy(message = RoomWording.askedNotice(row.friend.profile.displayName, row.isJam)) }
        }
    }

    fun unfriend(uid: String) = launchAction("Couldn't remove friend") { social.friends.unfriend(uid) }

    fun block(uid: String) = launchAction("Couldn't block") { social.friends.block(uid) }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    private fun launchAction(failure: String, busy: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (busy) _ui.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(message = "$failure: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                if (busy) _ui.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        /**
         * Playing something first, then online, then everyone else; most recently seen first
         * within offline friends, names alphabetically otherwise.
         */
        fun buildRows(
            friends: List<Friend>,
            unread: Map<String, Int>,
            myRequests: List<JoinRequest>,
            nowMs: Long,
        ): List<FriendRow> {
            val pending = myRequests.filter { it.status == JoinStatus.PENDING }.map { it.hostUid }.toSet()
            return friends.map { f ->
                FriendRow(
                    friend = f,
                    online = f.presence?.online == true,
                    status = ActivityText.statusLine(f.presence, nowMs),
                    activity = ActivityText.visibleActivity(f.presence),
                    unread = unread[f.profile.uid] ?: 0,
                    joinRequested = f.profile.uid in pending,
                )
            }.sortedWith(
                compareBy<FriendRow>(
                    { if (it.activity != null) 0 else if (it.online) 1 else 2 },
                    { -(it.friend.presence?.lastSeen ?: 0L) * (if (it.online) 0 else 1) },
                    { it.friend.profile.displayName.lowercase() },
                ),
            )
        }
    }
}
