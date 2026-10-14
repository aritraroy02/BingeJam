package com.syncit.firebase.social

import android.util.Base64
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.syncit.core.social.ActivityUpdate
import com.syncit.core.social.PresenceRepository
import com.syncit.core.sync.ServerClock
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.isRoomCode
import com.syncit.firebase.long
import com.syncit.firebase.string

/**
 * presence/{uid} {online, lastSeen, activity?}: written by the owner, readable by friends only.
 *
 * Online/offline works like room presence in FirebaseSyncTransport: on every (re)connect we
 * register a server-side onDisconnect that marks us offline and removes the activity, then mark
 * ourselves online. If the app dies or loses network the server runs it, so friends never see a
 * stale "watching …" from a dead phone. On reconnect the last activity is published again.
 */
class FirebasePresenceRepository internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
    private val serverClock: ServerClock,
) : PresenceRepository {

    private class Running(val uid: String, val ref: DatabaseReference, val listener: ValueEventListener)

    private val lock = Any()
    private var running: Running? = null

    /** What we last published, re-sent after a reconnect (the server removed it on disconnect). */
    @Volatile private var lastActivity: Map<String, Any?>? = null

    /** app + title of [lastActivity]; startedAt is kept while this stays the same. */
    @Volatile private var lastKey: String? = null

    /** Server time [lastActivity] started (estimated with [serverClock] when stamped by the server). */
    @Volatile private var lastStartedAt: Long? = null

    override fun start() {
        val uid = session.currentUid ?: return
        synchronized(lock) {
            if (running?.uid == uid) return
            stopLocked(markOffline = false)
            val ref = db.getReference("presence").child(uid)
            val connectedRef = db.getReference(".info/connected")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.getValue(Boolean::class.java) != true) return
                    ref.onDisconnect()
                        .updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP, "activity" to null))
                        .addOnSuccessListener {
                            val update = mutableMapOf<String, Any?>("online" to true, "lastSeen" to ServerValue.TIMESTAMP)
                            lastActivity?.let { update["activity"] = it }
                            ref.updateChildren(update)
                                .addOnFailureListener { Log.w(TAG, "Couldn't mark online", it) }
                        }
                        .addOnFailureListener { Log.w(TAG, "Couldn't register onDisconnect", it) }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            connectedRef.addValueEventListener(listener)
            running = Running(uid, ref, listener)
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked(markOffline = true) }
    }

    private fun stopLocked(markOffline: Boolean) {
        val r = running ?: return
        running = null
        db.getReference(".info/connected").removeEventListener(r.listener)
        r.ref.onDisconnect().cancel()
        // Must run while still signed in (FirebaseServices calls stop() before signing out).
        if (markOffline && session.currentUid == r.uid) {
            r.ref.updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP, "activity" to null))
        }
        lastActivity = null
        lastKey = null
        lastStartedAt = null
    }

    override suspend fun publishActivity(update: ActivityUpdate?) {
        val uid = session.currentUid ?: return
        val ref = db.getReference("presence").child(uid).child("activity")
        if (update == null) {
            lastActivity = null
            lastKey = null
            lastStartedAt = null
            ref.removeValue().await()
            return
        }
        val meta = update.metadata
        val title = meta?.title?.trim()?.take(MAX_META_LENGTH)?.takeIf { it.isNotEmpty() }
        val key = update.app.wire + "\u0000" + title.orEmpty()

        // startedAt only resets when the app or title changes. After a process restart we don't
        // know the previous value, so look at what the server has.
        var keepStartedAt: Long? = if (key == lastKey) lastStartedAt else null
        if (lastKey == null) {
            keepStartedAt = runCatching { ref.get().await() }.getOrNull()?.let { s ->
                val sameKey = s.child("app").string() == update.app.wire && s.child("title").string() == title
                s.child("startedAt").long().takeIf { sameKey }
            }
        }

        val fields = buildMap<String, Any?> {
            put("app", update.app.wire)
            title?.let { put("title", it) }
            meta?.subtitle?.trim()?.take(MAX_META_LENGTH)?.takeIf { it.isNotEmpty() }?.let { put("subtitle", it) }
            meta?.artworkJpeg?.takeIf { it.size <= MAX_ARTWORK_BYTES }?.let { put("artwork", Base64.encodeToString(it, Base64.NO_WRAP)) }
            put("playing", update.playing)
            meta?.durationMs?.takeIf { it > 0 }?.let { put("durationMs", it) }
            // Server-stamped when new; otherwise the earlier server value (rules allow ≤ now).
            put("startedAt", keepStartedAt ?: ServerValue.TIMESTAMP)
            update.roomCode?.takeIf { isRoomCode(it) }?.let { put("roomCode", it) }
        }
        lastKey = key
        lastStartedAt = keepStartedAt ?: serverClock.serverNowMs()
        // Republished after a reconnect with the remembered start time, not a fresh stamp.
        lastActivity = fields + ("startedAt" to lastStartedAt)
        ref.setValue(fields).await()
    }

    companion object {
        private const val TAG = "SyncItPresence"
        private const val MAX_META_LENGTH = 200

        /** Base64 grows 4/3, so this stays under the rules' 70,000-character limit. */
        private const val MAX_ARTWORK_BYTES = 52_000
    }
}
