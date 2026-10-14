package com.syncit.app.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.JoinStatus
import com.syncit.core.model.RoomInvite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * In-app social notifications shown over any screen (no push notifications in v2):
 * - host side: a friend asks to join what we're watching → Accept / Decline;
 * - a friend invites us into their room → Join / Not now;
 * - requester side: our "Ask to join" was accepted (→ join the room) or declined (gentle note).
 */
class SocialEventsViewModel(private val container: AppContainer) : ViewModel() {
    private val invites = container.social.watchInvites

    sealed interface NavEvent {
        /** We're the host and just made a room for a friend: open it (that starts syncing). */
        data class OpenRoom(val code: String) : NavEvent

        /** Join someone else's room straight away. */
        data class JoinRoom(val code: String) : NavEvent
    }

    private val events = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = events.receiveAsFlow()

    val joinRequests: StateFlow<List<JoinRequest>> = invites.observeIncomingJoinRequests()
        .map { list -> list.filter { it.status == JoinStatus.PENDING }.sortedBy { it.sentAt } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Invites, minus any for the room we're already syncing in. */
    val roomInvites: StateFlow<List<RoomInvite>> = combine(
        invites.observeInvites().catch { emit(emptyList()) },
        container.sessionController.state,
    ) { list, session -> list.filter { it.roomCode != session.roomCode }.sortedBy { it.sentAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val friendNames: StateFlow<Map<String, String>> = container.social.friends.observeFriends()
        .map { list -> list.associate { it.profile.uid to it.profile.displayName } }
        .catch { emit(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** uid of the request/invite being handled. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** Outcomes already acted on (the node stays until cleared, and may re-emit). */
    private val handled = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            invites.observeMyJoinRequests().catch { }.collect { list -> list.forEach { onMyRequest(it) } }
        }
    }

    private suspend fun onMyRequest(request: JoinRequest) {
        val key = "${request.hostUid}:${request.status}:${request.sentAt}"
        if (request.status == JoinStatus.PENDING || key in handled) return
        handled += key
        val host = friendNames.value[request.hostUid] ?: "Your friend"
        runCatching { invites.clearMyJoinRequest(request.hostUid) }
        // An answer from long ago (app was closed) shouldn't throw us into an old room.
        val fresh = container.transport.serverClock.serverNowMs() - request.sentAt < STALE_ANSWER_MS
        when (request.status) {
            JoinStatus.ACCEPTED -> {
                val code = request.roomCode
                if (code != null && fresh) {
                    _notice.value = "$host let you in. Joining…"
                    events.send(NavEvent.JoinRoom(code))
                }
            }
            JoinStatus.DECLINED -> if (fresh) _notice.value = "$host can't have you join right now. Maybe next time!"
            JoinStatus.PENDING -> Unit
        }
    }

    /**
     * Host accepts: use the room we're syncing in, or create one (as "Start" on Home does) and
     * open it, which starts the sync session. Then hand the code to the requester.
     */
    fun accept(request: JoinRequest) = act(request.fromUid, "Couldn't let ${request.fromDisplayName} in") {
        val running = container.sessionController.state.value.roomCode
        val code = running ?: container.transport.createRoom(container.prefs.displayName.ifBlank { "Host" })
            .also { container.prefs.lastRoomCode = it }
        invites.acceptJoin(request.fromUid, code)
        if (running == null) events.send(NavEvent.OpenRoom(code))
        _notice.value = "${request.fromDisplayName} is joining your watch party"
    }

    fun decline(request: JoinRequest) = act(request.fromUid, "Couldn't decline") { invites.declineJoin(request.fromUid) }

    fun joinInvite(invite: RoomInvite) = act(invite.fromUid, "Couldn't join") {
        runCatching { invites.dismissInvite(invite.fromUid) }
        events.send(NavEvent.JoinRoom(invite.roomCode))
    }

    fun dismissInvite(invite: RoomInvite) = act(invite.fromUid, "Couldn't dismiss") { invites.dismissInvite(invite.fromUid) }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun act(uid: String, failure: String, block: suspend () -> Unit) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = uid
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = "$failure: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = null
            }
        }
    }

    companion object {
        /** Answers to "Ask to join" older than this are cleared without acting on them. */
        const val STALE_ANSWER_MS = 30 * 60_000L
    }
}
