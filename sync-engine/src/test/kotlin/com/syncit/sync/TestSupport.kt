package com.syncit.sync

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaAction
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.RoomState
import com.syncit.core.sync.SyncClock

/** Both clocks move together; tests advance them explicitly. */
class FakeClock(
    var elapsed: Long = 1_000_000L,
    var server: Long = 1_700_000_000_000L,
) : SyncClock {
    override fun elapsedRealtimeMs(): Long = elapsed
    override fun serverNowMs(): Long = server

    fun advance(ms: Long) {
        elapsed += ms
        server += ms
    }
}

const val ME = "me"
const val HOST = "host"

val ALL_ACTIONS = setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.PLAY_PAUSE, MediaAction.SEEK_TO)
val NO_SEEK_ACTIONS = setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.PLAY_PAUSE)

/** A PLAYING reading sampled "now". */
fun FakeClock.playing(positionMs: Long, actions: Set<MediaAction> = ALL_ACTIONS, speed: Float = 1f) =
    snapshot(LocalPlaybackState.PLAYING, 3, positionMs, actions, speed)

/** A PAUSED reading sampled "now". */
fun FakeClock.paused(positionMs: Long, actions: Set<MediaAction> = ALL_ACTIONS) =
    snapshot(LocalPlaybackState.PAUSED, 2, positionMs, actions, 0f)

fun FakeClock.snapshot(
    state: LocalPlaybackState,
    rawState: Int,
    positionMs: Long,
    actions: Set<MediaAction> = ALL_ACTIONS,
    speed: Float = 1f,
) = PlaybackSnapshot(
    state = state,
    rawState = rawState,
    positionMs = positionMs,
    lastPositionUpdateElapsedMs = elapsed,
    speed = speed,
    actions = actions,
    rawActions = 0L,
)

/** A room state written "now" (server time) unless [updatedAt] is given. */
fun FakeClock.roomState(
    status: PlaybackStatus,
    positionMs: Long,
    seq: Long,
    by: String = HOST,
    updatedAt: Long = server,
) = RoomState(status, positionMs, updatedAt, by, seq)

/**
 * An engine attached to a playing app, aligned with a room playing at the same spot, and past
 * the attach-settle window. Afterwards local and room position are both
 * [positionMs] + attachSettleMs + 1.
 */
fun syncedPlaying(
    clock: FakeClock,
    actions: Set<MediaAction> = ALL_ACTIONS,
    positionMs: Long = 60_000,
    config: SyncConfig = SyncConfig(),
): DefaultSyncEngine {
    val engine = DefaultSyncEngine(ME, clock, config)
    engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, positionMs, seq = 1), emptyList())
    engine.onLocalSnapshot(clock.playing(positionMs, actions))
    clock.advance(config.attachSettleMs + 1)
    return engine
}

/** Same as [syncedPlaying] but paused at [positionMs]. */
fun syncedPaused(
    clock: FakeClock,
    actions: Set<MediaAction> = ALL_ACTIONS,
    positionMs: Long = 10_000,
    config: SyncConfig = SyncConfig(),
): DefaultSyncEngine {
    val engine = DefaultSyncEngine(ME, clock, config)
    engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, positionMs, seq = 1), emptyList())
    engine.onLocalSnapshot(clock.paused(positionMs, actions))
    clock.advance(config.attachSettleMs + 1)
    return engine
}
