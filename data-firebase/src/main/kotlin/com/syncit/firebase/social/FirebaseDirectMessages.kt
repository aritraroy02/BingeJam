package com.syncit.firebase.social

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.model.DirectMessage
import com.syncit.core.social.DirectMessages
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.NotFriendsException
import com.syncit.firebase.await
import com.syncit.firebase.combineAll
import com.syncit.firebase.isPermissionDenied
import com.syncit.firebase.long
import com.syncit.firebase.snapshots
import com.syncit.firebase.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One-to-one chat between friends: dms/{dmId}/{pushId} {from, text, sentAt}. The rules let only
 * the two participants read or write, and only while they're friends.
 *
 * Unread counts: dmReads/{me}/{friend} holds the server time we last opened the chat; messages
 * from the friend with a later sentAt are unread. Both are server timestamps, so device clocks
 * play no part.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseDirectMessages internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
) : DirectMessages {

    override fun observe(friendUid: String, limit: Int): Flow<List<DirectMessage>> =
        session.uid.flatMapLatest { me ->
            if (me == null || me == friendUid) {
                flowOf(emptyList())
            } else {
                db.getReference("dms").child(DmIds.dmId(me, friendUid))
                    .orderByChild("sentAt").limitToLast(limit.coerceIn(1, MAX_LIMIT))
                    .snapshots()
                    .map { s -> s?.children?.mapNotNull(SocialParser::message).orEmpty() }
            }
        }

    override suspend fun send(friendUid: String, text: String) {
        val me = session.requireUid()
        val trimmed = text.trim().take(MAX_TEXT_LENGTH)
        if (trimmed.isEmpty()) return
        try {
            db.getReference("dms").child(DmIds.dmId(me, friendUid)).push().setValue(
                mapOf("from" to me, "text" to trimmed, "sentAt" to ServerValue.TIMESTAMP),
            ).await()
        } catch (e: Exception) {
            if (e.isPermissionDenied()) throw NotFriendsException("You can only message friends")
            throw e
        }
    }

    override fun observeUnreadCounts(): Flow<Map<String, Int>> = session.uid.flatMapLatest { me ->
        if (me == null) return@flatMapLatest flowOf(emptyMap())
        val friendIds = db.getReference("friends").child(me).snapshots()
            .map { s -> s?.children?.mapNotNull { it.key }.orEmpty() }
            .distinctUntilChanged()
        val reads = db.getReference("dmReads").child(me).snapshots()
            .map { s -> s?.children?.mapNotNull { c -> c.key?.let { it to (c.long() ?: 0L) } }?.toMap().orEmpty() }
        combine(friendIds, reads) { friends, lastRead -> friends.map { it to (lastRead[it] ?: 0L) } }
            .distinctUntilChanged()
            .flatMapLatest { pairs ->
                // One small query per friend: their messages newer than our last read.
                combineAll(
                    pairs.map { (friend, since) ->
                        db.getReference("dms").child(DmIds.dmId(me, friend))
                            .orderByChild("sentAt").startAt((since + 1).toDouble()).limitToLast(MAX_UNREAD_COUNTED)
                            .snapshots()
                            .map { s -> friend to (s?.children?.count { it.child("from").string() == friend } ?: 0) }
                    },
                ).map { list -> list.filter { it.second > 0 }.toMap() }
            }
    }

    override suspend fun markRead(friendUid: String) {
        val me = session.requireUid()
        db.getReference("dmReads").child(me).child(friendUid).setValue(ServerValue.TIMESTAMP).await()
    }

    companion object {
        const val MAX_TEXT_LENGTH = 1000
        private const val MAX_LIMIT = 500

        /** Badges show "99+" beyond this, so there's no need to download more. */
        const val MAX_UNREAD_COUNTED = 100
    }
}
