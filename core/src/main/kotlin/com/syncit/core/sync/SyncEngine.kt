package com.syncit.core.sync

import com.syncit.core.model.EngineOutput
import com.syncit.core.model.Member
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.RoomState
import com.syncit.core.model.SyncMode
import com.syncit.core.model.SyncStatus

/**
 * Pure sync logic for one device in one room. Not thread-safe: call it from a single thread.
 *
 * Inputs: local snapshots, room updates and a periodic tick (about once a second).
 * Outputs: commands for the local app, a state to broadcast, and position reports.
 */
interface SyncEngine {
    val mode: SyncMode
    val status: SyncStatus

    /** Our position minus the room's expected position at the last drift check, if known. */
    val lastDriftMs: Long?

    /** A new local reading for the target app, or null if its session disappeared. */
    fun onLocalSnapshot(snapshot: PlaybackSnapshot?): EngineOutput

    /** The room's latest state (null if nobody has played yet) and members. */
    fun onRoom(state: RoomState?, members: List<Member>): EngineOutput

    /** Called about once a second; runs drift checks, catch-up timers and position reports. */
    fun onTick(): EngineOutput
}

fun interface SyncEngineFactory {
    fun create(myUid: String, clock: SyncClock): SyncEngine
}
