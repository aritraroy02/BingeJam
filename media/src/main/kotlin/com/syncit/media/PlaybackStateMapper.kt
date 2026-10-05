package com.syncit.media

import android.media.session.PlaybackState
import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaAction
import com.syncit.core.model.PlaybackSnapshot

/** Converts android.media.session.PlaybackState into the platform-free [PlaybackSnapshot]. */
object PlaybackStateMapper {

    // PlaybackState.ACTION_SET_PLAYBACK_SPEED is API 29; minSdk is 26, so use the bit directly.
    private const val ACTION_SET_PLAYBACK_SPEED = 1L shl 22

    private val ACTION_BITS: List<Pair<Long, MediaAction>> = listOf(
        PlaybackState.ACTION_STOP to MediaAction.STOP,
        PlaybackState.ACTION_PAUSE to MediaAction.PAUSE,
        PlaybackState.ACTION_PLAY to MediaAction.PLAY,
        PlaybackState.ACTION_REWIND to MediaAction.REWIND,
        PlaybackState.ACTION_SKIP_TO_PREVIOUS to MediaAction.SKIP_TO_PREVIOUS,
        PlaybackState.ACTION_SKIP_TO_NEXT to MediaAction.SKIP_TO_NEXT,
        PlaybackState.ACTION_FAST_FORWARD to MediaAction.FAST_FORWARD,
        PlaybackState.ACTION_SET_RATING to MediaAction.SET_RATING,
        PlaybackState.ACTION_SEEK_TO to MediaAction.SEEK_TO,
        PlaybackState.ACTION_PLAY_PAUSE to MediaAction.PLAY_PAUSE,
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to MediaAction.PLAY_FROM_MEDIA_ID,
        PlaybackState.ACTION_PLAY_FROM_SEARCH to MediaAction.PLAY_FROM_SEARCH,
        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to MediaAction.SKIP_TO_QUEUE_ITEM,
        PlaybackState.ACTION_PLAY_FROM_URI to MediaAction.PLAY_FROM_URI,
        PlaybackState.ACTION_PREPARE to MediaAction.PREPARE,
        PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID to MediaAction.PREPARE_FROM_MEDIA_ID,
        PlaybackState.ACTION_PREPARE_FROM_SEARCH to MediaAction.PREPARE_FROM_SEARCH,
        PlaybackState.ACTION_PREPARE_FROM_URI to MediaAction.PREPARE_FROM_URI,
        ACTION_SET_PLAYBACK_SPEED to MediaAction.SET_PLAYBACK_SPEED,
    )

    fun toSnapshot(state: PlaybackState): PlaybackSnapshot = PlaybackSnapshot(
        state = mapState(state.state),
        rawState = state.state,
        positionMs = state.position,
        // getLastPositionUpdateTime() is in SystemClock.elapsedRealtime() time base.
        lastPositionUpdateElapsedMs = state.lastPositionUpdateTime,
        speed = state.playbackSpeed,
        actions = decodeActions(state.actions),
        rawActions = state.actions,
    )

    fun decodeActions(actions: Long): Set<MediaAction> =
        ACTION_BITS.filter { (bit, _) -> actions and bit != 0L }.map { it.second }.toSet()

    fun mapState(raw: Int): LocalPlaybackState = when (raw) {
        PlaybackState.STATE_NONE -> LocalPlaybackState.NONE
        PlaybackState.STATE_STOPPED -> LocalPlaybackState.STOPPED
        PlaybackState.STATE_PAUSED -> LocalPlaybackState.PAUSED
        PlaybackState.STATE_PLAYING -> LocalPlaybackState.PLAYING
        PlaybackState.STATE_FAST_FORWARDING -> LocalPlaybackState.FAST_FORWARDING
        PlaybackState.STATE_REWINDING -> LocalPlaybackState.REWINDING
        PlaybackState.STATE_BUFFERING -> LocalPlaybackState.BUFFERING
        PlaybackState.STATE_ERROR -> LocalPlaybackState.ERROR
        PlaybackState.STATE_CONNECTING -> LocalPlaybackState.CONNECTING
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> LocalPlaybackState.SKIPPING_TO_PREVIOUS
        PlaybackState.STATE_SKIPPING_TO_NEXT -> LocalPlaybackState.SKIPPING_TO_NEXT
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> LocalPlaybackState.SKIPPING_TO_QUEUE_ITEM
        else -> LocalPlaybackState.UNKNOWN
    }

    /** "PLAYING (3)" style label for the diagnostics screen. */
    fun stateLabel(raw: Int): String = "${mapState(raw).name} ($raw)"
}
