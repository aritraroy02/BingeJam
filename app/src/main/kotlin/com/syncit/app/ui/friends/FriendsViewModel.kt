package com.syncit.app.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.social.UsernameRules
import com.syncit.core.model.FriendRequest
import com.syncit.core.model.UserProfile
import com.syncit.core.social.BlockedGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Relation { NONE, FRIEND, REQUESTED, INCOMING, BLOCKED }

data class SearchResult(val profile: UserProfile, val relation: Relation)

/** Find people by username, handle requests, manage blocked users. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class FriendsViewModel(container: AppContainer) : ViewModel() {
    private val friends = container.social.friends
    private val profiles = container.social.profiles
    private val auth = container.social.auth
    private val safety = container.social.safety

    /** Groups we exited and blocked (2026-11-10). */
    val blockedGroups: StateFlow<List<BlockedGroup>> = safety.observeBlockedGroups()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unblockGroup(group: BlockedGroup) = action(group.id, "Couldn't unblock the group") {
        safety.unblockGroup(group.id)
        _message.value = "Unblocked ${group.name}. Its admins can add you again."
    }

    /** Our own username, for the "Share my username" button. Read-only. */
    val myUsername: StateFlow<String?> = profiles.observeMyProfile()
        .map { it?.username }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** uids with an action in flight, so their buttons show progress and can't be double-tapped. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    val incoming: StateFlow<List<FriendRequest>> = friends.observeIncomingRequests()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outgoing: StateFlow<List<FriendRequest>> = friends.observeOutgoingRequests()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val friendUids = friends.observeFriends().map { list -> list.map { it.profile.uid }.toSet() }.catch { emit(emptySet()) }
    private val blockedUids = friends.observeBlocked().catch { emit(emptySet()) }

    val blocked: StateFlow<List<UserProfile>> = blockedUids.flatMapLatest { uids ->
        if (uids.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(uids.map { uid -> profiles.observeProfile(uid).catch { emit(null) } }) { arr ->
                arr.filterNotNull().sortedBy { it.username }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Usernames are private (user's decision, 2026-11-05): only the exact, full username finds
     * someone, no partial matches. Waits for a pause; newer queries cancel older ones.
     */
    private val rawResults = _query
        .map { UsernameRules.normalize(it) }
        .debounce(SEARCH_DEBOUNCE_MS)
        .mapLatest { q ->
            if (UsernameRules.problem(q) != null) return@mapLatest emptyList()
            _searching.value = true
            try {
                listOfNotNull(profiles.findByUsername(q)).filter { it.uid != auth.currentUid }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            } finally {
                _searching.value = false
            }
        }

    val results: StateFlow<List<SearchResult>> = combine(rawResults, friendUids, incoming, outgoing, blockedUids) {
            found, friendSet, inc, out, blockedSet ->
        found.map { p ->
            val relation = when (p.uid) {
                in friendSet -> Relation.FRIEND
                in blockedSet -> Relation.BLOCKED
                in inc.map { it.fromUid } -> Relation.INCOMING
                in out.map { it.toUid } -> Relation.REQUESTED
                else -> Relation.NONE
            }
            SearchResult(p, relation)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value.take(UsernameRules.MAX_LENGTH + 1)
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun add(profile: UserProfile) = action(profile.uid, "Couldn't send the request") {
        friends.sendRequest(profile.username)
        _message.value = "Request sent to @${profile.username}"
    }

    fun accept(request: FriendRequest) = action(request.fromUid, "Couldn't accept") {
        friends.accept(request.fromUid)
        _message.value = "You and @${request.fromUsername} are now friends"
    }

    fun decline(request: FriendRequest) = action(request.fromUid, "Couldn't decline") { friends.decline(request.fromUid) }

    fun cancel(request: FriendRequest) = action(request.toUid, "Couldn't cancel") { friends.cancel(request.toUid) }

    fun unblock(profile: UserProfile) = action(profile.uid, "Couldn't unblock") { friends.unblock(profile.uid) }

    /** Accept from a search result (they had already asked us). */
    fun acceptFrom(profile: UserProfile) {
        viewModelScope.launch {
            incoming.first().firstOrNull { it.fromUid == profile.uid }?.let(::accept)
        }
    }

    private fun action(uid: String, failure: String, block: suspend () -> Unit) {
        if (uid in _busy.value) return
        viewModelScope.launch {
            _busy.update { it + uid }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // FriendRequestException messages are written for people ("You're already friends").
                _message.value = e.message ?: failure
            } finally {
                _busy.update { it - uid }
            }
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
