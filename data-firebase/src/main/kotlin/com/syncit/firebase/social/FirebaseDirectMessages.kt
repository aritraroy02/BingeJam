package com.syncit.firebase.social

import android.os.SystemClock
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.WatchSessionReport
import com.syncit.core.social.DirectMessages
import com.syncit.core.sync.ServerClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One-to-one chat between friends: dms/{dmId}/{pushId} {from, text, sentAt, forwarded?}. The
 * rules let only the two participants read or write, and only while they're friends. The sender
 * may later delete a message for everyone (text removed, deleted = true); "delete for me" is
 * dmHidden/{me}/{dmId}/{id} = true. Watched-together cards share the thread ([WatchCards]).
 *
 * Unread counts: dmReads/{me}/{friend} holds the server time we last opened the chat; messages
 * from the friend with a later sentAt are unread. Both are server timestamps, so device clocks
 * play no part.
 *
 * Typing indicator: typing/{dmId}/{uid} = server timestamp ([TypingIndicator] has the timing).
 * The writer refreshes it at most every 3 s (monotonic throttle) and removes it on stop, on
 * [send] and, through onDisconnect, when the connection drops. Readers compare its age with
 * [clock] (server time), so an entry left behind by a killed app stops counting after 6 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseDirectMessages internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
    private val clock: ServerClock,
    private val push: PushRelay? = null,
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : DirectMessages {

    private val typingThrottle = TypingThrottle(elapsedRealtime)

    /**
     * dmIds whose typing entry has an onDisconnect removal registered on the current connection.
     * Firebase runs onDisconnect once, when the connection drops, and doesn't re-register it on
     * reconnect, so this is cleared whenever .info/connected goes false.
     */
    private val disconnectArmed = HashSet<String>()

    init {
        db.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) != true) synchronized(disconnectArmed) { disconnectArmed.clear() }
            }

            override fun onCancelled(error: DatabaseError) = Unit
        })
    }

    override fun observe(friendUid: String, limit: Int): Flow<List<DirectMessage>> =
        session.uid.flatMapLatest { me ->
            if (me == null || me == friendUid) {
                flowOf(emptyList())
            } else {
                val dmId = DmIds.dmId(me, friendUid)
                val messages = db.getReference("dms").child(dmId)
                    .orderByChild("sentAt").limitToLast(limit.coerceIn(1, MAX_LIMIT))
                    .snapshots()
                    .map { s -> s?.children?.mapNotNull(SocialParser::message).orEmpty() }
                // Deleted for me: dmHidden/{me}/{dmId}/{id}. Starts empty, so the chat shows at
                // once; a denied listener (rules not deployed yet) just hides nothing.
                val hidden = flow {
                    emit(emptySet())
                    emitAll(
                        db.getReference("dmHidden").child(me).child(dmId).snapshots()
                            .map { s -> s?.children?.mapNotNull { it.key }?.toSet().orEmpty() },
                    )
                }
                combine(messages, hidden.distinctUntilChanged()) { list, gone ->
                    if (gone.isEmpty()) list else list.filterNot { it.id in gone }
                }
            }
        }

    /** Also clears our typing indicator for this chat (callers needn't call setTyping(false)). */
    override suspend fun send(friendUid: String, text: String, forwarded: Boolean) {
        val me = session.requireUid()
        if (me != friendUid) stopTyping(DmIds.dmId(me, friendUid), me)
        val trimmed = text.trim().take(MAX_TEXT_LENGTH)
        if (trimmed.isEmpty()) return
        val ref = db.getReference("dms").child(DmIds.dmId(me, friendUid)).push()
        val value = buildMap<String, Any> {
            put("from", me)
            put("text", trimmed)
            put("sentAt", ServerValue.TIMESTAMP)
            if (forwarded) put("forwarded", true)
        }
        try {
            ref.setValue(value).await()
        } catch (e: Exception) {
            if (e.isPermissionDenied()) throw NotFriendsException("You can only message friends")
            throw e
        }
        ref.key?.let { push?.directMessage(friendUid, it) }
    }

    /**
     * Clears the text (and the forwarded label) and sets deleted = true, keeping from and sentAt.
     * The rules allow this only on our own, not yet deleted message. Then the relay is asked to
     * replace the friend's notification if it still shows this message.
     */
    override suspend fun deleteForEveryone(friendUid: String, messageId: String) {
        val me = session.requireUid()
        require(me != friendUid && messageId.isNotEmpty())
        val ref = db.getReference("dms").child(DmIds.dmId(me, friendUid)).child(messageId)
        ref.updateChildren(mapOf("text" to null, "forwarded" to null, "deleted" to true)).await()
        push?.messageDeleted(friendUid, messageId)
    }

    override suspend fun deleteForMe(friendUid: String, messageId: String) {
        val me = session.requireUid()
        require(me != friendUid && messageId.isNotEmpty())
        db.getReference("dmHidden").child(me).child(DmIds.dmId(me, friendUid)).child(messageId).setValue(true).await()
    }

    /**
     * Finds this session's card slot ([WatchCards]) and creates or extends it. Never pings the
     * relay: cards don't notify. The card's sentAt is when it was created (server time), so it
     * sits in the timeline where the session reached the minimum time together.
     */
    override suspend fun recordWatchedTogether(friendUid: String, report: WatchSessionReport): Long? {
        val me = session.requireUid()
        if (me == friendUid || !WatchCards.isValidBaseKey(report.sessionKey)) return null
        val title = report.title.trim().take(WatchCards.MAX_TITLE_LENGTH)
        if (title.isEmpty() || report.togetherMs < WatchCards.MIN_DURATION_MS) return null
        val thread = db.getReference("dms").child(DmIds.dmId(me, friendUid))
        var slot = 0
        var retried = false
        while (slot < WatchCards.MAX_SLOTS) {
            val ref = thread.child(WatchCards.slotKey(report.sessionKey, slot))
            val existing = ref.get().await().value as? Map<*, *>
            when (val decision = WatchCards.decide(existing, clock.serverNowMs())) {
                WatchCards.Decision.Next -> slot++
                is WatchCards.Decision.Join -> {
                    val duration = WatchCards.mergedDuration(decision.durationMs, report.togetherMs)
                    val items = WatchCards.mergedItems(decision.items, report.items)
                    ref.updateChildren(mapOf("durationMs" to duration, "items" to items, "lastAt" to ServerValue.TIMESTAMP)).await()
                    return duration
                }
                WatchCards.Decision.Create -> {
                    val duration = WatchCards.mergedDuration(0L, report.togetherMs)
                    val value = mapOf(
                        "kind" to WatchCards.KIND,
                        "by" to me,
                        "title" to title,
                        "service" to report.service.name,
                        "durationMs" to duration,
                        "items" to WatchCards.mergedItems(1, report.items),
                        "sentAt" to ServerValue.TIMESTAMP,
                        "lastAt" to ServerValue.TIMESTAMP,
                    )
                    try {
                        ref.setValue(value).await()
                        return duration
                    } catch (e: Exception) {
                        // The friend's phone created it a moment earlier: read it again and join.
                        if (!e.isPermissionDenied() || retried) throw e
                        retried = true
                    }
                }
            }
        }
        Log.w(TAG, "No free watched-together slot for ${report.sessionKey}")
        return null
    }

    override fun observeLatest(): Flow<Map<String, DirectMessage>> = session.uid.flatMapLatest { me ->
        if (me == null) return@flatMapLatest flowOf(emptyMap())
        val friendIds = db.getReference("friends").child(me).snapshots()
            .map { s -> s?.children?.mapNotNull { it.key }.orEmpty() }
            .distinctUntilChanged()
        // dmId → ids deleted for me. Starts empty so the list shows at once.
        val hidden = flow {
            emit(emptyMap())
            emitAll(
                db.getReference("dmHidden").child(me).snapshots().map { s ->
                    s?.children?.associate { d -> d.key.orEmpty() to d.children.mapNotNull { it.key }.toSet() }.orEmpty()
                },
            )
        }.distinctUntilChanged()
        friendIds.flatMapLatest { friends ->
            if (friends.isEmpty()) return@flatMapLatest flowOf(emptyMap())
            // One small query per friend: their last few messages (a few, so "deleted for me" can be skipped).
            val latest = combineAll(
                friends.map { friend ->
                    db.getReference("dms").child(DmIds.dmId(me, friend))
                        .orderByChild("sentAt").limitToLast(LATEST_WINDOW)
                        .snapshots()
                        .map { s -> friend to s?.children?.mapNotNull(SocialParser::message).orEmpty() }
                },
            )
            combine(latest, hidden) { lists, gone ->
                lists.mapNotNull { (friend, messages) ->
                    val skip = gone[DmIds.dmId(me, friend)].orEmpty()
                    messages.lastOrNull { it.id !in skip }?.let { friend to it }
                }.toMap()
            }
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

    /**
     * Fire-and-forget: the writes are queued locally and never awaited, so a keystroke while
     * offline doesn't leave a coroutine hanging. Failures (e.g. no longer friends) are only logged.
     */
    override suspend fun setTyping(friendUid: String, typing: Boolean) {
        val me = session.currentUid ?: return
        if (me == friendUid || friendUid.isEmpty()) return
        val dmId = DmIds.dmId(me, friendUid)
        if (!typing) {
            stopTyping(dmId, me)
            return
        }
        if (!typingThrottle.shouldWrite(dmId)) return
        val ref = typingRef(dmId, me)
        // Arm the removal before the first write on this connection, so the entry can't outlive
        // the connection. Both requests go out in this order.
        val arm = synchronized(disconnectArmed) { disconnectArmed.add(dmId) }
        if (arm) {
            ref.onDisconnect().removeValue().addOnFailureListener { e ->
                synchronized(disconnectArmed) { disconnectArmed.remove(dmId) }
                Log.d(TAG, "Typing onDisconnect not registered", e)
            }
        }
        ref.setValue(ServerValue.TIMESTAMP).addOnFailureListener { e -> Log.d(TAG, "Typing write failed", e) }
    }

    /** Removes our entry if we wrote one since the last stop (the throttle remembers). */
    private fun stopTyping(dmId: String, me: String) {
        if (!typingThrottle.clear(dmId)) return
        typingRef(dmId, me).removeValue().addOnFailureListener { e -> Log.d(TAG, "Typing removal failed", e) }
    }

    override fun observeTyping(friendUid: String): Flow<Boolean> = session.uid.flatMapLatest { me ->
        if (me == null || me == friendUid || friendUid.isEmpty()) return@flatMapLatest flowOf(false)
        // Only the friend's own entry is read, so ours never counts. A cancelled listener (no
        // longer friends) emits null: not typing.
        typingRef(DmIds.dmId(me, friendUid), friendUid).snapshots()
            .map { it?.long() }
            .distinctUntilChanged()
            .flatMapLatest { stampMs ->
                if (stampMs == null) {
                    flowOf(false)
                } else {
                    // Ticker: re-check the same entry until it's stale, comparing server time
                    // (clock.serverNowMs) with the server-written stamp. A refresh from the friend
                    // restarts this with the new stamp.
                    flow {
                        while (true) {
                            val now = clock.serverNowMs()
                            val fresh = TypingIndicator.isFresh(stampMs, now)
                            emit(fresh)
                            if (!fresh && now >= stampMs) break
                            delay(TypingIndicator.nextCheckDelayMs(stampMs, now))
                        }
                    }
                }
            }
            .distinctUntilChanged()
    }

    private fun typingRef(dmId: String, uid: String): DatabaseReference =
        db.getReference("typing").child(dmId).child(uid)

    companion object {
        private const val TAG = "SyncItDms"
        const val MAX_TEXT_LENGTH = 1000
        private const val MAX_LIMIT = 500

        /** Badges show "99+" beyond this, so there's no need to download more. */
        const val MAX_UNREAD_COUNTED = 100

        /** Messages read per chat for the Chats list, so a few "deleted for me" in a row are skipped. */
        private const val LATEST_WINDOW = 5
    }
}
