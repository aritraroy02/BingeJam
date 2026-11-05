package com.syncit.app.di

import com.syncit.core.session.SyncSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a chat's room "who's in" list right (user's request, 2026-11-05: rooms live inside chats,
 * people join later). When we start or join the room of a 1:1 chat or a group, we're added to that
 * chat's list; when the sync session moves to another room or stops, we're taken off it. The
 * database also drops us if the app dies or goes offline (onDisconnect, see ChatRoomLinks).
 */
class ChatRoomTracker(
    private val social: SocialServices,
    sessionController: SyncSessionController,
    private val scope: CoroutineScope,
) {
    private data class Active(val groupId: String?, val friendUid: String?, val code: String, var seen: Boolean = false)

    @Volatile
    private var active: Active? = null

    init {
        scope.launch {
            sessionController.state.map { it.roomCode }.distinctUntilChanged().collect { code ->
                val a = active ?: return@collect
                when {
                    code == a.code -> a.seen = true
                    // Only once the session has actually been in this room, so joining isn't undone
                    // in the moment before the room screen starts the session.
                    a.seen -> leave(a)
                }
            }
        }
    }

    /** We started or are joining the room [code] of a group ([groupId]) or a 1:1 chat ([friendUid]). */
    fun joined(code: String, groupId: String? = null, friendUid: String? = null) {
        val previous = active
        if (previous != null && (previous.code != code || previous.groupId != groupId || previous.friendUid != friendUid)) leave(previous)
        val next = Active(groupId, friendUid, code)
        active = next
        scope.launch {
            runCatching {
                when {
                    groupId != null -> social.groups.setInRoom(groupId, true)
                    friendUid != null -> social.dms.setInRoom(friendUid, true)
                }
            }
        }
    }

    private fun leave(a: Active) {
        if (active === a) active = null
        scope.launch {
            runCatching {
                when {
                    a.groupId != null -> social.groups.setInRoom(a.groupId, false)
                    a.friendUid != null -> social.dms.setInRoom(a.friendUid, false)
                }
            }
        }
    }
}
