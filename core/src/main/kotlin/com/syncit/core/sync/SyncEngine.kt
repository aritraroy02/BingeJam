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

    /**
     * The timeline restarts for a new item (a new track in a jam). Call it once per change, as
     * soon as the change is known, before feeding further snapshots. Works whether or not the
     * app is currently attached (hidden sessions are fine).
     *
     * Until the restart completes, the app's jumps and play/pause changes are part of the switch:
     * never broadcast, no drift checks, no alignment to the old item's room state.
     *
     * [leader] = true (jam host): once the app reports a settled (PLAYING/PAUSED) reading for the
     * new item, the engine broadcasts its state and live position as authoritative (reason SEEK).
     * There is no attach-settle window afterwards, so a pause right after is broadcast normally.
     * Other members' room writes are not applied until our own write has come back.
     *
     * [leader] = false (member): room writes that arrive after this call belong to the new item.
     * Once one has arrived and the app has settled on the new item, the engine aligns to it (seek
     * + play/pause, echo-suppressed). No attach-settle window afterwards.
     *
     * If the restart can't complete in time the engine falls back to the late-join behaviour.
     */
    fun onItemChanged(leader: Boolean): EngineOutput = EngineOutput.NONE
}

fun interface SyncEngineFactory {
    fun create(myUid: String, clock: SyncClock): SyncEngine
}
