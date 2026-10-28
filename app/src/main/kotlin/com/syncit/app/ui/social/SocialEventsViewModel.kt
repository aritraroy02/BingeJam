package com.syncit.app.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.social.RoomWording
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.JoinStatus
import com.syncit.core.model.RoomInvite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * In-app social notifications shown over any screen (no push notifications in v2):
 * - host side: a friend asks to join what we're watching, or to jam to what we're playing → Accept / Decline;
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

    /** Server time, re-read every 30 s so pending requests age out of the banner on their own. */
    private val now = flow {
        while (true) {
            emit(container.transport.serverClock.serverNowMs())
            delay(30_000)
        }
    }

    /** Pending requests younger than [STALE_MS]; older ones are declined automatically (init). */
    val joinRequests: StateFlow<List<JoinRequest>> = combine(
        invites.observeIncomingJoinRequests().catch { emit(emptyList()) },
        now,
    ) { list, nowMs -> freshPending(list, nowMs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Invites, minus any for the room we're already syncing in. */
    val roomInvites: StateFlow<List<RoomInvite>> = combine(
        invites.observeInvites().catch { emit(emptyList()) },
        container.sessionController.state,
    ) { list, session -> list.filter { it.roomCode != session.roomCode }.sortedBy { it.sentAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** uid -> "@username", to name the host when our request is answered. */
    private val friendNames: StateFlow<Map<String, String>> = container.social.friends.observeFriends()
        .map { list -> list.associate { it.profile.uid to "@${it.profile.username}" } }
        .catch { emit(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** uid of the request/invite being handled. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** Outcomes already acted on (the node stays until cleared, and may re-emit). */
    private val handled = mutableSetOf<String>()

    /** Stale pending requests already auto-declined. */
    private val autoDeclined = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            invites.observeMyJoinRequests().catch { }.collect { list -> list.forEach { onMyRequest(it) } }
        }
        // Host side: a request nobody answered within STALE_MS can't be accepted any more, so
        // decline it (the requester sees the usual gentle note, if they're still waiting).
        viewModelScope.launch {
            combine(invites.observeIncomingJoinRequests().catch { emit(emptyList()) }, now, ::Pair)
                .collect { (list, nowMs) ->
                    for (r in list) {
                        if (r.status != JoinStatus.PENDING || !isStale(r.sentAt, nowMs)) continue
                        if (!autoDeclined.add("${r.fromUid}:${r.sentAt}")) continue
                        runCatching { invites.declineJoin(r.fromUid) }
                    }
                }
        }
    }

    private suspend fun onMyRequest(request: JoinRequest) {
        val key = "${request.hostUid}:${request.status}:${request.sentAt}"
        if (request.status == JoinStatus.PENDING || key in handled) return
        handled += key
        val host = friendNames.value[request.hostUid] ?: "Your friend"
        runCatching { invites.clearMyJoinRequest(request.hostUid) }
        // An answer from long ago (app was closed) shouldn't throw us into an old room. Judged
        // from when the host answered, so a late but recent answer still works.
        val fresh = !isStale(request.answeredAt ?: request.sentAt, container.transport.serverClock.serverNowMs())
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
    fun accept(request: JoinRequest) = act(request.fromUid, "Couldn't let @${request.fromUsername} in") {
        // The banner may have sat on screen past the cut-off.
        if (isStale(request.sentAt, container.transport.serverClock.serverNowMs())) {
            invites.declineJoin(request.fromUid)
            _notice.value = "That request expired. Ask @${request.fromUsername} to send a new one."
            return@act
        }
        val running = container.sessionController.state.value.roomCode
        val code = running ?: container.transport.createRoom(container.prefs.displayName.ifBlank { "Host" })
            .also { container.prefs.lastRoomCode = it }
        invites.acceptJoin(request.fromUid, code)
        if (running == null) events.send(NavEvent.OpenRoom(code))
        _notice.value = RoomWording.joiningNotice(request)
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
        /**
         * Pending requests older than this (by sentAt) are hidden and declined on the host's side;
         * answers older than this (by answeredAt) are ignored on the requester's side.
         */
        const val STALE_MS = 30 * 60_000L

        /** Both arguments are server time. */
        fun isStale(at: Long, nowMs: Long): Boolean = nowMs - at >= STALE_MS

        fun freshPending(list: List<JoinRequest>, nowMs: Long): List<JoinRequest> =
            list.filter { it.status == JoinStatus.PENDING && !isStale(it.sentAt, nowMs) }.sortedBy { it.sentAt }
    }
}
