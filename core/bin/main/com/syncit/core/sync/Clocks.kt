package com.syncit.core.sync

/**
 * Server time, i.e. the clock that produced every ServerValue.TIMESTAMP in the room.
 * Implementations derive it from Firebase's .info/serverTimeOffset.
 */
interface ServerClock {
    fun serverNowMs(): Long
}

/**
 * The only time source the sync engine is allowed to use. Injected so tests can fake it.
 */
interface SyncClock {
    /** Local monotonic time, same base as PlaybackSnapshot.lastPositionUpdateElapsedMs. */
    fun elapsedRealtimeMs(): Long

    /** Estimated current server time, same base as RoomState.updatedAt. */
    fun serverNowMs(): Long
}
