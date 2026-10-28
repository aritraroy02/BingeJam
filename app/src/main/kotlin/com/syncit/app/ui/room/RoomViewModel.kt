package com.syncit.app.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.ChatMessage
import com.syncit.core.model.Friend
import com.syncit.core.model.Reaction
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StreamingService
import com.syncit.core.session.SyncSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RoomViewModel(val code: String, private val container: AppContainer) : ViewModel() {

    private val transport = container.transport

    val myUid: StateFlow<String?> = transport.currentUid

    val room: StateFlow<Room?> = transport.observeRoom(code)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Host name + what the host is playing (title, artwork); the room's hero. */
    val preview: StateFlow<RoomPreview?> = transport.observePreview(code)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sync: StateFlow<SyncSessionState> = container.sessionController.state

    val chat: StateFlow<List<ChatMessage>> = transport.observeChat(code)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Server time now, read on demand (chat times; [serverNow] ticks too often for that). */
    fun serverNowMs(): Long = transport.serverClock.serverNowMs()

    /** Server time, ticking so countdowns and drift readouts refresh. */
    val serverNow: StateFlow<Long> = flow {
        while (true) {
            emit(transport.serverClock.serverNowMs())
            delay(250)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), transport.serverClock.serverNowMs())

    private val _reactions = MutableStateFlow<List<Reaction>>(emptyList())

    /** Reactions from the last few seconds, oldest first. */
    val reactions: StateFlow<List<Reaction>> = _reactions.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Friends, for "Invite friends". */
    val friends: StateFlow<List<Friend>> = container.social.friends.observeFriends()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _invited = MutableStateFlow<Set<String>>(emptySet())

    /** Friends invited from this screen (cleared when the room screen is recreated). */
    val invited: StateFlow<Set<String>> = _invited.asStateFlow()

    /** Sends a room invite with what the host is playing, if known. */
    fun inviteFriend(uid: String) {
        if (uid in _invited.value) return
        _invited.update { it + uid }
        val service = preview.value?.service ?: room.value?.title?.service ?: sync.value.service
        val app = service?.let { s -> ActivityApp.entries.firstOrNull { it.syncService == s } }
        viewModelScope.launch {
            try {
                container.social.watchInvites.inviteToRoom(uid, code, app, preview.value?.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _invited.update { it - uid }
                _message.value = "Invite not sent: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private var reactionsJob: Job? = null

    /** Called when the room screen appears. Idempotent. */
    fun enter() {
        container.sessionController.start(code)
        if (reactionsJob?.isActive == true) return
        // Re-joining is idempotent; it restarts presence if the process was recreated since
        // Home joined the room.
        launchAction("Couldn't rejoin the room") {
            transport.joinRoom(code, container.prefs.displayName.ifBlank { "Guest" })
        }
        // Only reactions sent from now on (server time); old ones aren't replayed.
        val since = transport.serverClock.serverNowMs()
        reactionsJob = viewModelScope.launch {
            transport.observeReactions(code, since)
                .catch { }
                .collect { reaction ->
                    _reactions.update { (it + reaction).takeLast(MAX_VISIBLE_REACTIONS) }
                    launch {
                        delay(REACTION_VISIBLE_MS)
                        _reactions.update { list -> list.filterNot { it.id == reaction.id } }
                    }
                }
        }
    }

    /**
     * JamAutoOpen keys already acted on. Lives with this ViewModel, which is kept per room code
     * for the activity's lifetime, so coming back to the room screen doesn't reopen the app.
     */
    private val autoOpened = mutableSetOf<String>()

    fun autoOpenedKeys(): Set<String> = autoOpened

    /** True the first time for [key]; the caller then opens the app. */
    fun claimAutoOpen(key: String): Boolean = autoOpened.add(key)

    /** Jams: "Back to the jam" starts the host's current track on this phone. No-op otherwise. */
    fun followHost() = container.sessionController.followHost()

    fun setTitle(service: StreamingService, deepLink: String) = launchAction("Couldn't save the title") {
        transport.setTitle(code, service, deepLink.trim())
    }

    fun startCountdown() = launchAction("Couldn't start the countdown") {
        transport.startCountdown(code, COUNTDOWN_LEAD_MS)
    }

    fun sendChat(text: String) = launchAction("Message not sent") {
        transport.sendChat(code, container.prefs.displayName, text)
    }

    fun sendReaction(emoji: String) = launchAction("Reaction not sent") {
        transport.sendReaction(code, container.prefs.displayName, emoji)
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun leave(onLeft: () -> Unit) {
        container.sessionController.stop()
        reactionsJob?.cancel()
        viewModelScope.launch {
            try {
                transport.leaveRoom(code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: the server-side onDisconnect will mark us offline anyway.
            }
            container.prefs.lastRoomCode = null
            onLeft()
        }
    }

    private fun launchAction(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "$failure: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    companion object {
        /** 4 s lead → "Get ready", 3, 2, 1, PLAY. */
        const val COUNTDOWN_LEAD_MS = 4_000L
        private const val REACTION_VISIBLE_MS = 4_000L
        private const val MAX_VISIBLE_REACTIONS = 8
    }
}
