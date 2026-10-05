package com.syncit.sync

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.Member
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.RoomState

/** Position extrapolation. Every function is pure; callers pass "now" explicitly. */
object PositionMath {

    /**
     * Live position of the local app.
     *
     *   live = position + (nowElapsed - lastPositionUpdateTime) * speed     while PLAYING
     *   live = position                                                    otherwise
     *
     * Both times are SystemClock.elapsedRealtime(), so wall-clock changes don't matter.
     * Apps only re-publish position on state changes, so this is how we know where they are now.
     */
    fun localLivePositionMs(snapshot: PlaybackSnapshot, nowElapsedMs: Long): Long {
        if (snapshot.positionMs < 0) return snapshot.positionMs // POSITION_UNKNOWN
        if (snapshot.state != LocalPlaybackState.PLAYING) return snapshot.positionMs
        // An app that never sets lastPositionUpdateTime (0) gives us nothing to extrapolate from.
        if (snapshot.lastPositionUpdateElapsedMs <= 0) return snapshot.positionMs
        val sinceUpdateMs = (nowElapsedMs - snapshot.lastPositionUpdateElapsedMs).coerceAtLeast(0)
        // Some apps report speed 0 while playing; real playback is then 1x.
        val speed = if (snapshot.speed > 0f) snapshot.speed else 1f
        return snapshot.positionMs + (sinceUpdateMs * speed).toLong()
    }

    /**
     * Where the room says everyone should be right now.
     *
     *   expected = positionMs + (serverNow - updatedAt)     while playing
     *   expected = positionMs                               while paused
     *
     * updatedAt was stamped by the server when the write landed, and serverNow is our estimate
     * of the same server clock, so the network delay of the write is already included: a
     * device that hears about a "play" 300 ms late will compute a position 300 ms further on.
     */
    fun roomExpectedPositionMs(state: RoomState, serverNowMs: Long): Long {
        if (state.status != PlaybackStatus.PLAYING) return state.positionMs
        val sinceUpdateMs = (serverNowMs - state.updatedAt).coerceAtLeast(0)
        return state.positionMs + sinceUpdateMs
    }

    /**
     * A member's position extrapolated from their last report (reportedAt is server time),
     * assuming they've been following the room's play/pause state since. Null if they haven't
     * reported, or the report is older than [maxAgeMs].
     */
    fun memberExpectedPositionMs(
        member: Member,
        roomStatus: PlaybackStatus?,
        serverNowMs: Long,
        maxAgeMs: Long,
    ): Long? {
        val position = member.positionMs ?: return null
        val reportedAt = member.reportedAt ?: return null
        val ageMs = (serverNowMs - reportedAt).coerceAtLeast(0)
        if (ageMs > maxAgeMs) return null
        return if (roomStatus == PlaybackStatus.PLAYING) position + ageMs else position
    }
}
