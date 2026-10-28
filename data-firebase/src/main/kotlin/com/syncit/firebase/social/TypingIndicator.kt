package com.syncit.firebase.social

/**
 * Timing for the DM typing indicator (typing/{dmId}/{uid} = server timestamp). Pure Kotlin so
 * it's unit-tested; [FirebaseDirectMessages] does the reads and writes.
 *
 * The writer refreshes its timestamp every [REFRESH_MS] while typing; readers treat an entry as
 * "typing" while it is younger than [STALE_MS] in server time. So the indicator survives one
 * refresh plus latency, and still clears within a few seconds when the writer stops without
 * removing the entry (app killed before Firebase's onDisconnect fires).
 */
internal object TypingIndicator {
    /** Minimum gap between two timestamp writes while the user keeps typing (monotonic time). */
    const val REFRESH_MS = 3_000L

    /**
     * Age (server time) after which an entry no longer counts as typing. 2 × [REFRESH_MS]: a
     * refresh is due at 3 s, leaving ~3 s for the write to reach the server and the friend.
     */
    const val STALE_MS = 6_000L

    /**
     * How far in the future (by our server-clock estimate) an entry may be and still count. A
     * fresh entry is ~0 s old; a larger negative age means our estimate is off (e.g. not synced
     * with .info/serverTimeOffset yet). Bounding it keeps a bad estimate from showing "typing"
     * for long: the indicator can then last at most [STALE_MS] + this after the friend stops.
     */
    const val MAX_FUTURE_SKEW_MS = 3_000L

    /** How often observers re-check an unchanged entry, so it turns false once it's stale. */
    const val TICK_MS = 1_000L

    /** True if an entry written at [stampMs] (server time) still means "typing" at [serverNowMs]. */
    fun isFresh(stampMs: Long, serverNowMs: Long): Boolean {
        val ageMs = serverNowMs - stampMs
        return ageMs >= -MAX_FUTURE_SKEW_MS && ageMs < STALE_MS
    }

    /**
     * Delay before the next re-check of an entry at [stampMs]: until it goes stale (+1 ms so the
     * check lands after the boundary), capped at [TICK_MS] so a server-clock re-anchor is picked
     * up promptly.
     */
    fun nextCheckDelayMs(stampMs: Long, serverNowMs: Long): Long =
        (stampMs + STALE_MS - serverNowMs + 1).coerceIn(1L, TICK_MS)
}

/**
 * Per-chat write throttle for [TypingIndicator]. [elapsedRealtime] must be monotonic
 * (SystemClock.elapsedRealtime), never the wall clock. Thread-safe.
 */
internal class TypingThrottle(private val elapsedRealtime: () -> Long) {
    private val lastWriteAt = HashMap<String, Long>()

    /** True if a timestamp write for [dmId] is due now; records it as written. */
    @Synchronized
    fun shouldWrite(dmId: String): Boolean {
        val now = elapsedRealtime()
        val last = lastWriteAt[dmId]
        if (last != null && now - last < TypingIndicator.REFRESH_MS) return false
        lastWriteAt[dmId] = now
        return true
    }

    /**
     * Forgets [dmId] so the next [shouldWrite] writes at once. True if we had written an entry
     * since the last clear, i.e. there is something to remove.
     */
    @Synchronized
    fun clear(dmId: String): Boolean = lastWriteAt.remove(dmId) != null
}
