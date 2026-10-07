package com.syncit.core.model

/** Mirrors android.media.session.PlaybackState.STATE_* without depending on Android. */
enum class LocalPlaybackState {
    NONE,
    STOPPED,
    PAUSED,
    PLAYING,
    FAST_FORWARDING,
    REWINDING,
    BUFFERING,
    ERROR,
    CONNECTING,
    SKIPPING_TO_PREVIOUS,
    SKIPPING_TO_NEXT,
    SKIPPING_TO_QUEUE_ITEM,
    UNKNOWN,
}

/** Mirrors the android.media.session.PlaybackState.ACTION_* bits. */
enum class MediaAction {
    STOP,
    PAUSE,
    PLAY,
    REWIND,
    SKIP_TO_PREVIOUS,
    SKIP_TO_NEXT,
    FAST_FORWARD,
    SET_RATING,
    SEEK_TO,
    PLAY_PAUSE,
    PLAY_FROM_MEDIA_ID,
    PLAY_FROM_SEARCH,
    SKIP_TO_QUEUE_ITEM,
    PLAY_FROM_URI,
    PREPARE,
    PREPARE_FROM_MEDIA_ID,
    PREPARE_FROM_SEARCH,
    PREPARE_FROM_URI,
    SET_PLAYBACK_SPEED,
}

/**
 * One reading of another app's MediaSession PlaybackState.
 *
 * Time base: [lastPositionUpdateElapsedMs] is SystemClock.elapsedRealtime() at the moment
 * the app sampled [positionMs] (that's what PlaybackState.getLastPositionUpdateTime() returns).
 * It is a local monotonic clock, not wall-clock time.
 */
data class PlaybackSnapshot(
    val state: LocalPlaybackState,
    val rawState: Int,
    /** Position in ms at [lastPositionUpdateElapsedMs], or [POSITION_UNKNOWN]. */
    val positionMs: Long,
    val lastPositionUpdateElapsedMs: Long,
    val speed: Float,
    val actions: Set<MediaAction>,
    val rawActions: Long,
) {
    val supportsPlayPause: Boolean
        get() = MediaAction.PLAY in actions || MediaAction.PAUSE in actions || MediaAction.PLAY_PAUSE in actions

    val supportsSeek: Boolean
        get() = MediaAction.SEEK_TO in actions

    companion object {
        /** Same value as PlaybackState.PLAYBACK_POSITION_UNKNOWN. */
        const val POSITION_UNKNOWN = -1L
    }
}

/**
 * An active media session on this device. [snapshot] is null if the app hasn't published a
 * state yet; [metadata] is null if it hasn't published any metadata.
 */
data class MediaSessionInfo(
    /** Stable for the lifetime of the session; used to address commands. */
    val sessionKey: String,
    val packageName: String,
    val snapshot: PlaybackSnapshot?,
    val metadata: NowPlayingMetadata? = null,
)
