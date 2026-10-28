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
        /** True while the app is buffering/skipping: kept alive until it settles (see [hold]). */
        var held: Boolean = false,
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
     * The app is buffering/skipping: it hasn't finished applying our commands yet. Keep every
     * live expectation until the app settles, however long one buffering callback lasts, but
     * never past [maxHoldMs] after the command was issued:
     *
     *   expiresAt = issuedAt + maxHoldMs        while held
     */
    fun hold(now: Long) {
        prune(now)
        for (e in pending) {
            e.held = true
            e.expiresAt = maxOf(e.expiresAt, e.issuedAt + maxHoldMs)
        }
    }

    /**
     * The app settled (PLAYING/PAUSED again). Held expectations get one normal window from now,
     * so repeated callbacks right after settling still match, still capped at [maxHoldMs]:
     *
     *   expiresAt = min(issuedAt + maxHoldMs, now + windowMs)
     *
     * Releasing before matching the settling callback is fine: a held expectation still has
     * windowMs to go (unless it's already past maxHoldMs, when it should expire anyway).
     */
    fun release(now: Long) {
        for (e in pending) {
            if (!e.held) continue
            e.held = false
            e.expiresAt = minOf(e.issuedAt + maxHoldMs, now + windowMs)
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
