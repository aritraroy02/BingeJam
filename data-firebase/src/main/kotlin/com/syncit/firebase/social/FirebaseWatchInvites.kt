package com.syncit.firebase.social

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.JoinStatus
import com.syncit.core.model.RoomInvite
import com.syncit.core.model.UserProfile
import com.syncit.core.social.WatchInvites
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.NotFriendsException
import com.syncit.firebase.await
import com.syncit.firebase.combineAll
import com.syncit.firebase.isPermissionDenied
import com.syncit.firebase.isRoomCode
import com.syncit.firebase.snapshots
import com.syncit.firebase.social.SocialParser.wire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * "Ask to join" and "Invite", between friends only (enforced by the rules).
 *
 *   joinRequests/{host}/{from}  {fromUsername, fromDisplayName, app, title?, status, roomCode?, sentAt}
 *       the requester writes it as "pending"; the host may only change status, stamping
 *       answeredAt (server time) and adding the room code when accepting; either side may delete it.
 *   invites/{to}/{from}         {fromUsername, fromDisplayName, roomCode, app?, title?, sentAt}
 *       the inviter must be a member of that room; either side may delete it.
 *
 * Our own outgoing join requests live under each host, so [observeMyJoinRequests] listens to
 * joinRequests/{friend}/{me} for every friend (requests can only go to friends anyway).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseWatchInvites internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
) : WatchInvites {

    override suspend fun requestToJoin(hostUid: String, app: ActivityApp, title: String?) {
        require(app.joinable) { "${app.displayName} can't be joined as a watch party" }
        val me = session.requireUid()
        require(hostUid != me) { "That's your own activity" }
        val profile = myProfile(me)
        writeOrNotFriends {
            db.getReference("joinRequests").child(hostUid).child(me).setValue(
                buildMap {
                    put("fromUsername", profile.username)
                    put("fromDisplayName", profile.displayName)
                    put("app", app.wire)
                    title?.trim()?.take(MAX_TITLE_LENGTH)?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
                    put("status", JoinStatus.PENDING.wire())
                    put("sentAt", ServerValue.TIMESTAMP)
                },
            ).await()
        }
    }

    override fun observeIncomingJoinRequests(): Flow<List<JoinRequest>> = session.uid.flatMapLatest { me ->
        if (me == null) {
            flowOf(emptyList())
        } else {
            db.getReference("joinRequests").child(me).snapshots().map { s ->
                s?.children?.mapNotNull { SocialParser.joinRequest(me, it) }.orEmpty().sortedByDescending { it.sentAt }
            }
        }
    }

    override fun observeMyJoinRequests(): Flow<List<JoinRequest>> = session.uid.flatMapLatest { me ->
        if (me == null) return@flatMapLatest flowOf(emptyList())
        db.getReference("friends").child(me).snapshots()
            .map { s -> s?.children?.mapNotNull { it.key }.orEmpty() }
            .distinctUntilChanged()
            .flatMapLatest { hosts ->
                combineAll(
                    hosts.map { host ->
                        db.getReference("joinRequests").child(host).child(me).snapshots()
                            .map { SocialParser.joinRequest(host, it) }
                    },
                ).map { list -> list.filterNotNull().sortedByDescending { it.sentAt } }
            }
    }

    override suspend fun acceptJoin(fromUid: String, roomCode: String) {
        val me = session.requireUid()
        require(isRoomCode(roomCode)) { "Invalid room code" }
        db.getReference("joinRequests").child(me).child(fromUid).updateChildren(
            mapOf("status" to JoinStatus.ACCEPTED.wire(), "roomCode" to roomCode, "answeredAt" to ServerValue.TIMESTAMP),
        ).await()
    }

    override suspend fun declineJoin(fromUid: String) {
        val me = session.requireUid()
        db.getReference("joinRequests").child(me).child(fromUid).updateChildren(
            mapOf("status" to JoinStatus.DECLINED.wire(), "roomCode" to null, "answeredAt" to ServerValue.TIMESTAMP),
        ).await()
    }

    override suspend fun clearMyJoinRequest(hostUid: String) {
        val me = session.requireUid()
        db.getReference("joinRequests").child(hostUid).child(me).removeValue().await()
    }

    override suspend fun inviteToRoom(friendUid: String, roomCode: String, app: ActivityApp?, title: String?) {
        val me = session.requireUid()
        require(isRoomCode(roomCode)) { "Invalid room code" }
        val profile = myProfile(me)
        writeOrNotFriends {
            db.getReference("invites").child(friendUid).child(me).setValue(
                buildMap {
                    put("fromUsername", profile.username)
                    put("fromDisplayName", profile.displayName)
                    put("roomCode", roomCode)
                    app?.takeIf { it.joinable }?.let { put("app", it.wire) }
                    title?.trim()?.take(MAX_TITLE_LENGTH)?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
                    put("sentAt", ServerValue.TIMESTAMP)
                },
            ).await()
        }
    }

    override fun observeInvites(): Flow<List<RoomInvite>> = session.uid.flatMapLatest { me ->
        if (me == null) {
            flowOf(emptyList())
        } else {
            db.getReference("invites").child(me).snapshots().map { s ->
                s?.children?.mapNotNull(SocialParser::invite).orEmpty().sortedByDescending { it.sentAt }
            }
        }
    }

    override suspend fun dismissInvite(fromUid: String) {
        val me = session.requireUid()
        db.getReference("invites").child(me).child(fromUid).removeValue().await()
    }

    private suspend fun myProfile(me: String): UserProfile =
        SocialParser.profile(me, db.getReference("users").child(me).get().await())
            ?: throw IllegalStateException("Pick a username first")

    private suspend fun writeOrNotFriends(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: Exception) {
            if (e.isPermissionDenied()) throw NotFriendsException("You can only do that with friends")
            throw e
        }
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 200
    }
}
