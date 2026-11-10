package com.syncit.firebase.social

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.config.FriendLinks
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.UserProfile
import com.syncit.core.social.BlockedGroup
import com.syncit.core.social.FriendInvites
import com.syncit.core.social.FriendsRepository
import com.syncit.core.social.GroupChats
import com.syncit.core.social.ProfileRepository
import com.syncit.core.social.ReportReason
import com.syncit.core.social.Safety
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.isPermissionDenied
import com.syncit.firebase.snapshots
import com.syncit.firebase.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

/**
 * Invite tokens live in users_private/{uid}/inviteToken (only the owner reads it). A request that
 * carries it ("via") is accepted by the push relay as soon as it's sent; [acceptInvited] is the
 * fallback when the relay couldn't.
 */
internal class FirebaseFriendInvites(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
    private val profiles: ProfileRepository,
    private val friends: FriendsRepository,
) : FriendInvites {

    private val random = SecureRandom()

    override suspend fun myToken(): String {
        val ref = tokenRef()
        ref.get().await().string()?.takeIf(::isToken)?.let { return it }
        return resetToken()
    }

    override suspend fun resetToken(): String {
        val token = newToken()
        tokenRef().setValue(token).await()
        return token
    }

    override suspend fun lookUp(username: String): UserProfile? = profiles.findByUsername(username)

    override suspend fun acceptInvited() {
        val me = session.requireUid()
        val token = tokenRef().get().await().string() ?: return
        val requests = db.getReference("friendRequests").child(me).get().await()
        for (r in requests.children) {
            val from = r.key ?: continue
            if (r.child("via").string() == token) runCatching { friends.accept(from) }
        }
    }

    private fun tokenRef() = db.getReference("users_private").child(session.requireUid()).child("inviteToken")

    private fun isToken(value: String) = value.length == FriendLinks.TOKEN_LENGTH && value.all { it in FriendLinks.TOKEN_ALPHABET }

    private fun newToken(): String = buildString {
        repeat(FriendLinks.TOKEN_LENGTH) { append(FriendLinks.TOKEN_ALPHABET[random.nextInt(FriendLinks.TOKEN_ALPHABET.length)]) }
    }
}

/**
 * reports/{uid}/{pushId}: write-once, never read by the app (the developer reviews them in the
 * Firebase console). blockedGroups/{uid}/{groupId} = {name, at}: the rules stop admins adding us
 * back or inviting us while it's there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FirebaseSafety(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
    private val groups: GroupChats,
) : Safety {

    override suspend fun reportUser(uid: String, name: String, reason: ReportReason, details: String?, evidence: List<DirectMessage>, blocked: Boolean) =
        report("user", uid, name, reason, details, evidence, blocked)

    override suspend fun reportGroup(groupId: String, name: String, reason: ReportReason, details: String?, evidence: List<DirectMessage>, blocked: Boolean) =
        report("group", groupId, name, reason, details, evidence, blocked)

    private suspend fun report(kind: String, target: String, name: String, reason: ReportReason, details: String?, evidence: List<DirectMessage>, blocked: Boolean) {
        val me = session.requireUid()
        val entry = buildMap<String, Any> {
            put("kind", kind)
            put("target", target)
            put("targetName", name.take(MAX_NAME))
            put("reason", reason.wire)
            details?.trim()?.takeIf { it.isNotEmpty() }?.let { put("details", it.take(MAX_DETAILS)) }
            put("blocked", blocked)
            val items = evidence.filter { !it.deleted && it.text.isNotBlank() }.takeLast(Safety.MAX_EVIDENCE)
            if (items.isNotEmpty()) {
                put(
                    "evidence",
                    items.mapIndexed { i, m ->
                        i.toString() to mapOf("from" to m.fromUid, "text" to m.text.take(MAX_TEXT), "sentAt" to m.sentAt)
                    }.toMap(),
                )
            }
            put("at", ServerValue.TIMESTAMP)
        }
        db.getReference("reports").child(me).push().setValue(entry).await()
    }

    override suspend fun blockGroup(groupId: String, name: String) {
        val me = session.requireUid()
        try {
            groups.leave(groupId)
        } catch (e: Exception) {
            // Not a member any more (removed, or blocking from an invite): nothing to leave.
            if (!e.isPermissionDenied()) throw e
        }
        db.reference.updateChildren(
            mapOf(
                "blockedGroups/$me/$groupId" to mapOf("name" to name.take(MAX_GROUP_NAME), "at" to ServerValue.TIMESTAMP),
                "groupInvites/$me/$groupId" to null,
            ),
        ).await()
    }

    override suspend fun unblockGroup(groupId: String) {
        val me = session.requireUid()
        db.getReference("blockedGroups").child(me).child(groupId).removeValue().await()
    }

    override fun observeBlockedGroups(): Flow<List<BlockedGroup>> = session.uid.flatMapLatest { me ->
        if (me == null) {
            flowOf(emptyList())
        } else {
            db.getReference("blockedGroups").child(me).snapshots().map { s ->
                s?.children?.mapNotNull { c -> c.key?.let { BlockedGroup(it, c.child("name").string().orEmpty()) } }.orEmpty()
                    .sortedBy { it.name.lowercase() }
            }
        }
    }

    private companion object {
        const val MAX_NAME = 60
        const val MAX_GROUP_NAME = 50
        const val MAX_DETAILS = 500
        const val MAX_TEXT = 1000
    }
}
