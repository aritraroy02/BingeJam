package com.syncit.media.service

import com.syncit.core.model.MediaSessionInfo

/**
 * Jams: holds back a "position jumped to the start" reading for a moment, in case it's really a
 * track change whose new metadata hasn't arrived yet.
 *
 * Apps publish PlaybackState and MediaMetadata separately. If the state for the next track (at ~0)
 * comes first, the old track's metadata still looks current and the engine would broadcast it as
 * a user SEEK to 0: a member's early auto-advance would restart the host's song, and a host's own
 * skip would send SEEK@0 for the old track before onItemChanged. Holding the reading for up to
 * [holdMs] lets the metadata catch up; then the track change goes through onItemChanged (host) or
 * JamFollower (member). If the metadata doesn't change, it was a real seek to the start and is
 * released (just later).
 *
 * Pure; times are SystemClock.elapsedRealtime() passed in.
 */
internal class JumpHold(
    /** Longest a reading is held back. */
    private val holdMs: Long = 800,
    /** "Near the start" = live position below this. */
    private val nearStartMs: Long = 3_000,
    /** ...and at least this far back from where the app was (the engine's seek threshold). */
    private val minJumpMs: Long = 3_000,
) {
    private var heldSinceMs: Long? = null

    /** While a hold is active, how long until it ends (for scheduling a re-check); else null. */
    fun remainingMs(nowMs: Long): Long? = heldSinceMs?.let { it + holdMs - nowMs }?.takeIf { it > 0 }

    /**
     * Should [next] be kept from the engine for now? [previous] is the reading the engine last got.
     */
    fun shouldHold(previous: MediaSessionInfo?, next: MediaSessionInfo?, nowMs: Long): Boolean {
        val prevSnap = previous?.snapshot
        val nextSnap = next?.snapshot
        if (prevSnap == null || nextSnap == null || previous.sessionKey != next.sessionKey) return release()
        // Metadata already changed (or can't tell): the track-change paths handle it.
        if (JamTracks.sameItem(previous.metadata, next.metadata) != true) return release()

        // Timing: both positions extrapolated to the same local "now" (elapsedRealtime).
        val before = JamTracks.livePositionMs(prevSnap, nowMs)
        val after = JamTracks.livePositionMs(nextSnap, nowMs)
        val jumpedToStart = after in 0 until nearStartMs && before >= 0 && before - after > minJumpMs
        if (!jumpedToStart) return release()

        val since = heldSinceMs ?: nowMs.also { heldSinceMs = it }
        return nowMs - since < holdMs
    }

    fun reset() {
        heldSinceMs = null
    }

    private fun release(): Boolean {
        heldSinceMs = null
        return false
    }
}
