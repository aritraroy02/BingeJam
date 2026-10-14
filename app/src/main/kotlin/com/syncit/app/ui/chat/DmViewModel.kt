package com.syncit.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.social.ActivityText
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Friend
import com.syncit.core.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-to-one chat with a friend. Opening it (and every new message while open) marks it read. */
class DmViewModel(val friendUid: String, private val container: AppContainer) : ViewModel() {
    private val social = container.social

    val myUid: String? get() = social.auth.currentUid

    val profile: StateFlow<UserProfile?> = social.profiles.observeProfile(friendUid)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [friend] is null once they're no longer friends (DMs are friends-only). */
    data class FriendState(val loaded: Boolean, val friend: Friend?)

    val friend: StateFlow<FriendState> = social.friends.observeFriends()
        .map { list -> FriendState(true, list.firstOrNull { it.profile.uid == friendUid }) }
        .catch { emit(FriendState(true, null)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FriendState(false, null))

    val messages: StateFlow<List<DirectMessage>?> = social.dms.observe(friendUid)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun statusLine(friend: Friend?): String =
        ActivityText.statusLine(friend?.presence, container.transport.serverClock.serverNowMs())

    fun markRead() {
        viewModelScope.launch { runCatching { social.dms.markRead(friendUid) } }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                social.dms.send(friendUid, trimmed.take(MAX_LENGTH))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Message not sent: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    companion object {
        const val MAX_LENGTH = 1_000
    }
}
