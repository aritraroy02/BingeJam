package com.syncit.sync

import com.syncit.core.model.BroadcastReason
import com.syncit.core.model.EngineOutput
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.StateBroadcast
import com.syncit.core.model.SyncCommand
import com.syncit.core.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** onItemChanged(): a new track in a jam restarts the timeline, for the leader (host) and members. */
class ItemChangeTest {

    private val clock = FakeClock()
    private val config = SyncConfig()

    // After syncedPlaying(), local and room (seq 1, written by HOST) are both at 64_001.

    /** Leader changes track at "now"; the app skips and starts the new track 300 ms later. */
    private fun leaderSwitchesTrack(engine: DefaultSyncEngine) {
        assertEquals(EngineOutput.NONE, engine.onItemChanged(leader = true))
        clock.advance(100)
        assertEquals(EngineOutput.NONE, engine.onLocalSnapshot(clock.skippingToNext(64_101)))
        clock.advance(200)
        // Jump from 64 s to the new track's start: part of the switch, not a user seek.
        assertEquals(EngineOutput.NONE, engine.onLocalSnapshot(clock.playing(0)))
    }

    // ---- Leader -----------------------------------------------------------------------------

    @Test
    fun leader_broadcastsNewItemAfterSettleMin() {
        val engine = syncedPlaying(clock)
        leaderSwitchesTrack(engine)

        clock.advance(config.itemSettleMinMs - 301) // 799 ms after the change
        assertNull(engine.onTick().broadcast)
        clock.advance(1)
        val out = engine.onTick()
        // Live position: 0 sampled 500 ms ago while playing.
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 500, BroadcastReason.SEEK), out.broadcast)
        assertTrue(out.commands.isEmpty())
    }

    @Test
    fun leader_pauseRightAfterTrackChange_isBroadcastNotUndone() {
        val engine = syncedPlaying(clock)
        leaderSwitchesTrack(engine)
        clock.advance(500)
        assertEquals(500L, engine.onTick().broadcast?.positionMs)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 500, seq = 2, by = ME), emptyList())

        // 1.8 s after the change (inside what used to be the 4 s re-attach settle window).
        clock.advance(1_000)
        val pause = engine.onLocalSnapshot(clock.paused(1_500))
        assertEquals(StateBroadcast(PlaybackStatus.PAUSED, 1_500, BroadcastReason.PAUSE), pause.broadcast)
        assertTrue(pause.commands.isEmpty())

        engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 1_500, seq = 3, by = ME), emptyList())
        clock.advance(5_000)
        assertTrue(engine.onTick().commands.isEmpty())
    }

    @Test
    fun leader_duringSwitch_noDriftCheckAndOthersWritesNotApplied() {
        val engine = syncedPlaying(clock)
        engine.onItemChanged(leader = true)
        clock.advance(100)
        engine.onLocalSnapshot(clock.playing(0))

        // A drift check against the old item's room position (64 s) would seek the new track.
        assertTrue(engine.onTick().commands.isEmpty())
        // A member's write about the old item arrives mid-switch.
        val remote = engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 64_101, seq = 2, by = "b"), emptyList())
        assertTrue(remote.commands.isEmpty())

        // The leader's state for the new item is authoritative.
        clock.advance(700)
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 700, BroadcastReason.SEEK), engine.onTick().broadcast)
    }

    @Test
    fun leader_othersWritesWaitUntilOurOwnWriteLands() {
        val engine = syncedPlaying(clock)
        leaderSwitchesTrack(engine)
        clock.advance(500)
        assertEquals(500L, engine.onTick().broadcast?.positionMs)

        // Ordered before ours by the server, but delivered before our own write comes back.
        assertTrue(engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 64_000, seq = 2, by = "b"), emptyList()).commands.isEmpty())
        assertTrue(engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 500, seq = 3, by = ME), emptyList()).commands.isEmpty())

        // After ours landed, later writes apply as usual.
        clock.advance(1_000)
        val later = engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 1_500, seq = 4, by = "b"), emptyList())
        assertEquals(listOf(SyncCommand.Pause), later.commands)
    }

    @Test
    fun leader_ownWriteGuard_expiresIfOurWriteNeverComesBack() {
        val engine = syncedPlaying(clock)
        leaderSwitchesTrack(engine)
        clock.advance(500)
        engine.onTick()

        clock.advance(config.itemOwnWriteWaitMs)
        val later = engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 3_500, seq = 2, by = "b"), emptyList())
        assertEquals(listOf(SyncCommand.Pause), later.commands)
    }

    @Test
    fun leader_newStatePublishedJustBeforeMetadata_countsAsFresh() {
        val engine = syncedPlaying(clock)
        // The app publishes the new track's state first: without an item change that's a scrub.
        assertEquals(
            StateBroadcast(PlaybackStatus.PLAYING, 0, BroadcastReason.SEEK),
            engine.onLocalSnapshot(clock.playing(0)).broadcast,
        )
        // The new metadata arrives 300 ms later (within itemFreshToleranceMs).
        clock.advance(300)
        engine.onItemChanged(leader = true)
        clock.advance(config.itemSettleMinMs)
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 1_100, BroadcastReason.SEEK), engine.onTick().broadcast)
    }

    @Test
    fun leader_staleReading_isTakenAfterSettleMax() {
        val engine = syncedPlaying(clock)
        // The app's last reading was sampled 4 s before the change and it never re-publishes.
        engine.onItemChanged(leader = true)
        clock.advance(config.itemSettleMinMs)
        assertNull(engine.onTick().broadcast)

        clock.advance(config.itemSettleMaxMs - config.itemSettleMinMs)
        // PLAYING@60_000 sampled 4_001 + 3_000 ms ago.
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 67_001, BroadcastReason.SEEK), engine.onTick().broadcast)
    }

    @Test
    fun leader_appNeverSettles_fallsBackToLateJoinAlignment() {
        val engine = syncedPlaying(clock)
        engine.onItemChanged(leader = true)
        clock.advance(100)
        engine.onLocalSnapshot(clock.buffering(0))

        clock.advance(config.itemRestartTimeoutMs - 101)
        assertTrue(engine.onTick().commands.isEmpty())
        clock.advance(1)
        // Same as the old re-attach path: line up with the room (64_001 + 8_000).
        assertEquals(listOf(SyncCommand.SeekTo(72_001)), engine.onTick().commands)
    }

    @Test
    fun leader_emptyRoom_isSeededWithoutWaitingForAttachSettle() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(null, emptyList())
        engine.onLocalSnapshot(clock.playing(30_000))
        clock.advance(1_000)
        engine.onItemChanged(leader = true)
        clock.advance(100)
        engine.onLocalSnapshot(clock.playing(0))
        clock.advance(700)
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 700, BroadcastReason.SEEK), engine.onTick().broadcast)
    }

    // ---- Member -----------------------------------------------------------------------------

    @Test
    fun member_ownJumpNotBroadcast_alignsToLeadersNewState() {
        val engine = syncedPlaying(clock)
        assertEquals(EngineOutput.NONE, engine.onItemChanged(leader = false))
        clock.advance(100)
        assertEquals(EngineOutput.NONE, engine.onLocalSnapshot(clock.skippingToNext(64_101)))
        clock.advance(300)
        assertEquals(EngineOutput.NONE, engine.onLocalSnapshot(clock.playing(0)))

        // The leader's restart write: its new track is already at 5 s.
        clock.advance(600)
        val out = engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 5_000, seq = 2), emptyList())
        assertEquals(listOf(SyncCommand.SeekTo(5_000)), out.commands)
        assertNull(out.broadcast)

        // Our seek lands through buffering: echo, not a user seek.
        clock.advance(200)
        assertNull(engine.onLocalSnapshot(clock.buffering(5_000)).broadcast)
        clock.advance(1_000)
        assertNull(engine.onLocalSnapshot(clock.playing(5_000)).broadcast)
    }

    @Test
    fun member_pauseRightAfterFollowing_isBroadcast() {
        val engine = syncedPlaying(clock)
        engine.onItemChanged(leader = false)
        clock.advance(100)
        engine.onLocalSnapshot(clock.playing(0))
        clock.advance(400)
        assertTrue(engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 400, seq = 2), emptyList()).commands.isEmpty())

        clock.advance(1_000)
        val out = engine.onLocalSnapshot(clock.paused(1_400))
        assertEquals(StateBroadcast(PlaybackStatus.PAUSED, 1_400, BroadcastReason.PAUSE), out.broadcast)
        assertTrue(out.commands.isEmpty())
    }

    @Test
    fun member_whileWaiting_noDriftCorrectionAgainstOldItem() {
        val engine = syncedPlaying(clock)
        engine.onItemChanged(leader = false)
        clock.advance(100)
        engine.onLocalSnapshot(clock.playing(0))

        assertTrue(engine.onTick().commands.isEmpty())
        clock.advance(config.driftCheckIntervalMs)
        val out = engine.onTick()
        assertTrue(out.commands.isEmpty())
        assertNull(out.broadcast)
        assertEquals(SyncStatus.SYNCING, engine.status)
    }

    @Test
    fun member_whileWaiting_ownPlayPauseChangesNotBroadcast() {
        val engine = syncedPlaying(clock)
        engine.onItemChanged(leader = false)
        clock.advance(100)
        assertNull(engine.onLocalSnapshot(clock.paused(0)).broadcast)
        clock.advance(300)
        assertNull(engine.onLocalSnapshot(clock.playing(0)).broadcast)
    }

    @Test
    fun member_hiddenDuringSwitch_alignsOnReattach_withoutSettleWindow() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(null) // the coordinator hides the app while it switches tracks
        engine.onItemChanged(leader = false)

        clock.advance(1_000)
        // The leader's write arrives while we're detached: nothing to command yet.
        assertTrue(engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 0, seq = 2), emptyList()).commands.isEmpty())

        clock.advance(1_000)
        // Our app is on the leader's track now, 5 s in; the room expects 1 s.
        assertEquals(listOf(SyncCommand.SeekTo(1_000)), engine.onLocalSnapshot(clock.playing(5_000)).commands)
        clock.advance(500)
        assertNull(engine.onLocalSnapshot(clock.playing(1_000)).broadcast) // our seek landing

        // A real pause 1.5 s after re-attaching is broadcast, not undone.
        clock.advance(1_000)
        val out = engine.onLocalSnapshot(clock.paused(2_000))
        assertEquals(StateBroadcast(PlaybackStatus.PAUSED, 2_000, BroadcastReason.PAUSE), out.broadcast)
        assertTrue(out.commands.isEmpty())
    }

    @Test
    fun member_ourOwnOldWriteComingBack_isNotTheNewItem() {
        val engine = syncedPlaying(clock)
        assertEquals(PlaybackStatus.PAUSED, engine.onLocalSnapshot(clock.paused(64_001)).broadcast?.status)
        engine.onItemChanged(leader = false)
        clock.advance(100)
        engine.onLocalSnapshot(clock.playing(0))

        // Our pause (about the old item) lands after the change: don't align to it.
        assertTrue(engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 64_001, seq = 2, by = ME), emptyList()).commands.isEmpty())

        clock.advance(400)
        val out = engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 0, seq = 3), emptyList())
        assertEquals(listOf(SyncCommand.Pause), out.commands)
    }

    @Test
    fun member_newStateBeforeAppSettles_waitsForApp() {
        val engine = syncedPlaying(clock)
        engine.onItemChanged(leader = false)
        clock.advance(100)
        engine.onLocalSnapshot(clock.skippingToNext(64_101))
        clock.advance(100)
        assertTrue(engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 2), emptyList()).commands.isEmpty())

        clock.advance(500)
        // Room expects 10_000 + 500; the app started the track at 0.
        assertEquals(listOf(SyncCommand.SeekTo(10_500)), engine.onLocalSnapshot(clock.playing(0)).commands)
    }

    @Test
    fun member_noWriteForNewItem_fallsBackToLateJoinAlignment() {
        val engine = syncedPlaying(clock)
        engine.onItemChanged(leader = false)
        clock.advance(100)
        engine.onLocalSnapshot(clock.playing(0))

        clock.advance(config.itemRestartTimeoutMs - 101)
        assertTrue(engine.onTick().commands.isEmpty())
        clock.advance(1)
        // Old behaviour: align to whatever the room says (64_001 + 8_000).
        assertEquals(listOf(SyncCommand.SeekTo(72_001)), engine.onTick().commands)
    }

    // ---- End of track: the app moves on by itself, the item change is known ~1 s later ----
    // syncedPlaying(positionMs = 195_000): local and room at 199_001, near the track's end.

    @Test
    fun endOfTrack_leader_jumpToZeroBeforeItemChange_isNotASeek() {
        val engine = syncedPlaying(clock, positionMs = 195_000)
        // New track loading at 0, before the new metadata reaches the coordinator.
        assertNull(engine.onLocalSnapshot(clock.buffering(0)).broadcast)
        clock.advance(700)
        engine.onItemChanged(leader = true)
        clock.advance(200)
        assertNull("the jump to 0 is the new track, not a scrub", engine.onLocalSnapshot(clock.playing(0)).broadcast)

        clock.advance(config.itemSettleMinMs - 200)
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 600, BroadcastReason.SEEK), engine.onTick().broadcast)
    }

    @Test
    fun endOfTrack_member_jumpToZeroBeforeItemChange_isNotASeek() {
        val engine = syncedPlaying(clock, positionMs = 195_000)
        assertNull(engine.onLocalSnapshot(clock.skippingToNext(0)).broadcast)
        clock.advance(900)
        engine.onItemChanged(leader = false)
        clock.advance(100)
        assertNull(engine.onLocalSnapshot(clock.playing(0)).broadcast)
        // Even past the transient hold, nothing is broadcast for the old jump.
        clock.advance(config.transientHoldMs)
        assertNull(engine.onTick().broadcast)
    }
}
