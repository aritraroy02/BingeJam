package com.syncit.sync

import com.syncit.core.model.PlaybackStatus
import kotlin.math.abs

/**
 * Remembers what we expect the local app to report after we apply a remote command, so
 * that report isn't mistaken for a user action and broadcast back. Without this, two
 * phones would bounce the same pause back and forth forever.
 *
 * All times are elapsedRealtime (ms).
 */
internal class EchoSuppressor(
    private val windowMs: Long,
    private val maxHoldMs: Long,
    private val seekToleranceMs: Long,
) {
    private class Expectation(
        val status: PlaybackStatus?,
        val positionMs: Long?,
        val issuedAt: Long,
        var expiresAt: Long,
    )

    private val pending = mutableListOf<Expectation>()

    fun expectStatus(status: PlaybackStatus, now: Long) {
        pending += Expectation(status, null, now, now + windowMs)
    }

    fun expectSeek(positionMs: Long, now: Long) {
        pending += Expectation(null, positionMs, now, now + windowMs)
    }

    /** Matching doesn't consume: apps often repeat the same state within the window. */
    fun matchesStatus(status: PlaybackStatus, now: Long): Boolean {
        prune(now)
        return pending.any { it.status == status }
    }

    /**
     * A seek we issued to X at time t shows up as position ≈ X (paused) or
     * ≈ X + (now − t) (playing). Accept either, within the seek threshold.
     */
    fun matchesSeek(positionMs: Long, now: Long): Boolean {
        prune(now)
        return pending.any { e ->
            val target = e.positionMs ?: return@any false
            abs(positionMs - target) <= seekToleranceMs ||
                abs(positionMs - (target + (now - e.issuedAt))) <= seekToleranceMs
        }
    }

    /**
     * The app is buffering/connecting: it hasn't finished applying our command yet, so keep
     * waiting a little longer, but never past [maxHoldMs] after the command.
     */
    fun extend(now: Long) {
        for (e in pending) {
            e.expiresAt = maxOf(e.expiresAt, minOf(now + windowMs, e.issuedAt + maxHoldMs))
        }
    }

    fun hasPending(now: Long): Boolean {
        prune(now)
        return pending.isNotEmpty()
    }

    fun clear() = pending.clear()

    private fun prune(now: Long) {
        pending.removeAll { it.expiresAt <= now }
    }
}
