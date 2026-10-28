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
import com.syncit.firebase.FirebaseSession
import com.syncit.firebase.await
import com.syncit.firebase.long
import com.syncit.firebase.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * presence/{uid}: written by the owner, readable by friends only.
 *
 *   online, lastSeen                    connection state (server time)
 *   activity { app, title?, subtitle?, artwork?, playing, durationMs?, startedAt, inWatchParty }
 *
 * The room code is never published: [ActivityUpdate.inWatchParty] only says "in a synced room";
 * friends get in through "Ask to join", which the host approves.
 *
 * Connection: like room presence in FirebaseSyncTransport, every (re)connect first registers a
 * server-side onDisconnect (offline + activity removed, so friends never see a stale "watching…"
 * from a dead phone), then marks us online, then re-publishes the activity. Online/lastSeen and
 * the activity are separate writes, so a rejected activity write can never leave us "offline".
 *
 * Cost: after the first full write, only fields that changed are sent (updateChildren). A
 * play/pause toggle sends `playing` alone; the ~70 KB artwork is only re-sent when its bytes change.
 *
 * startedAt is stamped by the server (ServerValue.TIMESTAMP) when the app or title changes, then
 * read back, and only that server value is ever re-published (rules: startedAt ≤ now). A local
 * estimate is never written, so it can't be ahead of server time.
 *
 * Writes are not awaited while holding the lock: Firebase queues them in order while offline,
 * and a failed write only makes the next publish a full one.
 */
class FirebasePresenceRepository internal constructor(
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
) : PresenceRepository {

    private class Running(val uid: String, val ref: DatabaseReference, val listener: ValueEventListener)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val connectionLock = Any()
    private var running: Running? = null

    /** Set by [retire] while the account is being deleted: nothing is written for this uid. */
    @Volatile private var retiredUid: String? = null

    // ---- Activity state, guarded by [mutex] ----

    /** What the tracker wants published (null = nothing). Re-sent after a reconnect. */
    private var desired: ActivityUpdate? = null

    /** Activity fields (without startedAt) we believe the server holds; null = unknown/none. */
    private var serverFields: Map<String, Any>? = null

    /** app + title of what we last wrote in full; startedAt belongs to it. */
    private var key: String? = null

    /** Server-stamped startedAt for [key], read back from the server; null = not known yet. */
    private var startedAt: Long? = null

    /** Bumped on every full write and on clears, so late read-backs/failures for older writes are ignored. */
    private var generation = 0L

    override fun start() {
        val uid = session.currentUid ?: return
        if (uid == retiredUid) return
        synchronized(connectionLock) {
            if (running?.uid == uid) return
            stopConnectionLocked(markOffline = false)
            val ref = db.getReference("presence").child(uid)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.getValue(Boolean::class.java) != true) return
                    ref.onDisconnect()
                        .updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP, "activity" to null))
                        .addOnSuccessListener {
                            // Separate from the activity: this one can't be rejected by activity validation.
                            ref.updateChildren(mapOf("online" to true, "lastSeen" to ServerValue.TIMESTAMP))
                                .addOnFailureListener { Log.w(TAG, "Couldn't mark online", it) }
                            // The server removed the activity when we dropped; send it again in full.
                            scope.launch { republish(uid) }
                        }
                        .addOnFailureListener { Log.w(TAG, "Couldn't register onDisconnect", it) }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            db.getReference(".info/connected").addValueEventListener(listener)
            running = Running(uid, ref, listener)
        }
    }

    /**
     * Account deletion: stop for good for [uid] so the tracker can't re-create presence/{uid}
     * after the eraser removed it. [stop] cancels the onDisconnect and its "offline" write lands
     * before the eraser's update (same connection, writes stay in order).
     */
    internal fun retire(uid: String) {
        retiredUid = uid
        stop()
    }

    /** Deletion failed or was cancelled; the account still exists, so [start] works again. */
    internal fun unretire(uid: String) {
        if (retiredUid == uid) retiredUid = null
    }

    override fun stop() {
        synchronized(connectionLock) { stopConnectionLocked(markOffline = true) }
        scope.launch {
            mutex.withLock {
                desired = null
                forgetLocked()
            }
        }
    }

    private fun stopConnectionLocked(markOffline: Boolean) {
        val r = running ?: return
        running = null
        db.getReference(".info/connected").removeEventListener(r.listener)
        r.ref.onDisconnect().cancel()
        // Must run while still signed in (FirebaseServices calls stop() before signing out).
        if (markOffline && session.currentUid == r.uid) {
            r.ref.updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP, "activity" to null))
        }
    }

    override suspend fun publishActivity(update: ActivityUpdate?) {
        val uid = session.currentUid ?: return
        mutex.withLock {
            if (uid == retiredUid) return
            desired = update
            publishLocked(uid)
        }
    }

    private suspend fun republish(uid: String) {
        mutex.withLock {
            serverFields = null // gone on the server (onDisconnect), so the next write is a full one
            if (desired != null && session.currentUid == uid && uid != retiredUid) publishLocked(uid)
        }
    }

    private fun forgetLocked() {
        generation++
        serverFields = null
        key = null
        startedAt = null
    }

    private suspend fun publishLocked(uid: String) {
        val ref = db.getReference("presence").child(uid).child("activity")
        val update = desired
        if (update == null) {
            val hadSomething = key != null || serverFields != null
            forgetLocked()
            if (hadSomething) ref.removeValue().addOnFailureListener { Log.w(TAG, "Couldn't clear activity", it) }
            return
        }
        val fields = activityFields(update)
        val newKey = update.app.wire + "\u0000" + (fields["title"] ?: "")
        val known = serverFields

        if (known != null && newKey == key) {
            // Same title: send only what changed (null removes a field).
            val diff = buildMap<String, Any?> {
                for ((k, v) in fields) if (known[k] != v) put(k, v)
                for (k in known.keys) if (k !in fields) put(k, null)
            }
            if (diff.isEmpty()) return
            serverFields = fields
            val gen = generation
            ref.updateChildren(diff).addOnFailureListener { e ->
                Log.w(TAG, "Activity update rejected; next publish will be a full write", e)
                scope.launch { mutex.withLock { if (generation == gen) serverFields = null } }
            }
            return
        }

        // Full write. Keep the server's startedAt if it's still the same app + title.
        var keep: Long? = if (newKey == key) startedAt else null
        if (key == null) {
            // First publish since start/clear (e.g. after a process restart): see what the server has.
            keep = runCatching { ref.get().await() }.getOrNull()?.let { s ->
                val same = s.child("app").string() == update.app.wire && s.child("title").string() == fields["title"]
                s.child("startedAt").long().takeIf { same }
            }
        }
        generation++
        val gen = generation
        key = newKey
        startedAt = keep
        serverFields = fields
        val task = ref.setValue(fields + ("startedAt" to (keep ?: ServerValue.TIMESTAMP)))
        scope.launch {
            try {
                task.await()
                if (keep != null) return@launch
                // Read back the server-stamped value; that's the only startedAt we ever re-send.
                val stamped = ref.child("startedAt").get().await().long()
                mutex.withLock { if (generation == gen) startedAt = stamped }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Activity write failed; next publish will be a full write", e)
                mutex.withLock { if (generation == gen) serverFields = null }
            }
        }
    }

    /** Activity fields except startedAt. Values are compared for the diff, so artwork is a String. */
    private fun activityFields(update: ActivityUpdate): Map<String, Any> {
        val meta = update.metadata
        return buildMap {
            put("app", update.app.wire)
            meta?.title?.trim()?.take(MAX_META_LENGTH)?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
            meta?.subtitle?.trim()?.take(MAX_META_LENGTH)?.takeIf { it.isNotEmpty() }?.let { put("subtitle", it) }
            meta?.artworkJpeg?.takeIf { it.size <= MAX_ARTWORK_BYTES }
                ?.let { put("artwork", Base64.encodeToString(it, Base64.NO_WRAP)) }
            put("playing", update.playing)
            meta?.durationMs?.takeIf { it > 0 }?.let { put("durationMs", it) }
            put("inWatchParty", update.inWatchParty)
        }
    }

    companion object {
        private const val TAG = "SyncItPresence"
        private const val MAX_META_LENGTH = 200

        /** Base64 grows 4/3, so this stays under the rules' 70,000-character limit. */
        private const val MAX_ARTWORK_BYTES = 52_000
    }
}
