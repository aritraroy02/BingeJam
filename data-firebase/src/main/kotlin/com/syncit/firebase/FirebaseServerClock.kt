package com.syncit.firebase

import android.os.SystemClock
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.syncit.core.sync.ServerClock

/**
 * Estimates Firebase server time.
 *
 * Firebase publishes .info/serverTimeOffset, defined as (server time − device wall clock).
 * Reading the wall clock on every call would let a user changing the clock, or an NTP jump,
 * shift our sync math. So we read the wall clock exactly once per offset update to set an
 * anchor, then advance from that anchor with SystemClock.elapsedRealtime(), which is
 * monotonic:
 *
 *   anchorServer  = wallClockAtSample + offset
 *   serverNow()   = anchorServer + (elapsedRealtime() − elapsedAtSample)
 *
 * This is the only place in the app that touches the wall clock, and only to interpret
 * Firebase's offset, which is defined against it.
 */
class FirebaseServerClock internal constructor(
    db: FirebaseDatabase,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ServerClock {

    @Volatile private var anchorServerMs: Long = wallClock()
    @Volatile private var anchorElapsedMs: Long = elapsedRealtime()

    /** False until Firebase has sent at least one offset. */
    @Volatile var isSynced: Boolean = false
        private set

    init {
        db.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val offsetMs = (snapshot.value as? Number)?.toLong() ?: return
                // Sample both clocks back to back so the anchor pair is consistent.
                val elapsed = elapsedRealtime()
                anchorServerMs = wallClock() + offsetMs
                anchorElapsedMs = elapsed
                isSynced = true
            }

            override fun onCancelled(error: DatabaseError) = Unit
        })
    }

    override fun serverNowMs(): Long = anchorServerMs + (elapsedRealtime() - anchorElapsedMs)
}
