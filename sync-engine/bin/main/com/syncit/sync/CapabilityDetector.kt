package com.syncit.sync

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaAction
import com.syncit.core.model.PlaybackSnapshot
import kotlin.math.abs

/**
 * Learns at runtime what the attached app supports. Reset whenever the session goes away,
 * because a reopened app may behave differently.
 */
internal class CapabilityDetector(private val config: SyncConfig) {

    /** Union of every action seen. Some apps drop actions while buffering; don't flap on that. */
    private val seenActions = mutableSetOf<MediaAction>()

    var positionReliable: Boolean = true
        private set

    private var playStartedAt: Long? = null
    private var positionAtPlayStart: Long = 0

    val supportsPlayPause: Boolean
        get() = MediaAction.PLAY in seenActions || MediaAction.PAUSE in seenActions || MediaAction.PLAY_PAUSE in seenActions

    val supportsSeek: Boolean
        get() = MediaAction.SEEK_TO in seenActions

    fun observe(snapshot: PlaybackSnapshot, now: Long) {
        seenActions += snapshot.actions

        if (snapshot.positionMs < 0) {
            // PLAYBACK_POSITION_UNKNOWN: the app tells us outright it has no position.
            positionReliable = false
            return
        }

        when (snapshot.state) {
            LocalPlaybackState.PLAYING -> if (playStartedAt == null) {
                playStartedAt = now
                positionAtPlayStart = snapshot.positionMs
            }

            LocalPlaybackState.PAUSED, LocalPlaybackState.STOPPED -> {
                val started = playStartedAt
                // After real play time, a pause must report a position that moved. If it says 0,
                // or exactly where play started, the app never updates its position.
                if (started != null && now - started >= config.positionCheckMinPlayMs) {
                    val moved = abs(snapshot.positionMs - positionAtPlayStart) >= config.positionCheckToleranceMs
                    if (snapshot.positionMs == 0L || !moved) positionReliable = false
                }
                playStartedAt = null
            }

            else -> Unit
        }
    }

    /** Called when seeks repeatedly fail to fix drift. */
    fun markPositionUnreliable() {
        positionReliable = false
    }

    fun reset() {
        seenActions.clear()
        positionReliable = true
        playStartedAt = null
        positionAtPlayStart = 0
    }
}
