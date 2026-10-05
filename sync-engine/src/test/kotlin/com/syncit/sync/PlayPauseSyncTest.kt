package com.syncit.sync

import com.syncit.core.model.BroadcastReason
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.StateBroadcast
import com.syncit.core.model.SyncCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2: play/pause both ways, echo suppression, own-uid filtering, conflicts. */
class PlayPauseSyncTest {

    private val clock = FakeClock()

    @Test
    fun localPause_isBroadcastWithCurrentPosition() {
        val engine = syncedPlaying(clock)
        // syncedPlaying leaves us at 60_000 + 4_001
        val out = engine.onLocalSnapshot(clock.paused(64_001))
        assertEquals(StateBroadcast(PlaybackStatus.PAUSED, 64_001, BroadcastReason.PAUSE), out.broadcast)
        assertTrue(out.commands.isEmpty())
    }

    @Test
    fun localPlay_isBroadcast() {
        val engine = syncedPaused(clock)
        val out = engine.onLocalSnapshot(clock.playing(10_000))
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 10_000, BroadcastReason.PLAY), out.broadcast)
    }

    @Test
    fun remotePlay_isApplied() {
        val engine = syncedPaused(clock)
        val out = engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 2), emptyList())
        assertEquals(listOf(SyncCommand.Play), out.commands)
        assertNull(out.broadcast)
    }

    @Test
    fun remotePause_isApplied() {
        val engine = syncedPlaying(clock)
        val out = engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 64_001, seq = 2), emptyList())
        assertEquals(listOf(SyncCommand.Pause), out.commands)
    }

    @Test
    fun echo_localCallbackAfterRemotePlay_isNotBroadcast() {
        val engine = syncedPaused(clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 2), emptyList())

        clock.advance(500) // within the 1.5 s echo window
        val out = engine.onLocalSnapshot(clock.playing(10_000))

        assertNull("our own applied play must not bounce back", out.broadcast)
    }

    @Test
    fun echo_isOnlySuppressedWithinWindow_butMatchingRoomStillNotBroadcast() {
        val engine = syncedPaused(clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 2), emptyList())

        // App takes 2 s to start; window expired, but it now agrees with the room
        // (room expected = 10_000 + 2_000), so there's nothing to tell anyone.
        clock.advance(2_000)
        val out = engine.onLocalSnapshot(clock.playing(12_000))

        assertNull(out.broadcast)
    }

    @Test
    fun genuineUserPause_afterEchoWindow_isBroadcast() {
        val engine = syncedPaused(clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 2), emptyList())
        clock.advance(500)
        engine.onLocalSnapshot(clock.playing(10_000))

        clock.advance(3_000)
        val out = engine.onLocalSnapshot(clock.paused(13_000))

        assertEquals(StateBroadcast(PlaybackStatus.PAUSED, 13_000, BroadcastReason.PAUSE), out.broadcast)
    }

    @Test
    fun eventsWrittenByOurOwnUid_areIgnored() {
        val engine = syncedPaused(clock)
        val out = engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 2, by = ME), emptyList())
        assertTrue(out.commands.isEmpty())
    }

    @Test
    fun conflict_staleSeqIsIgnored() {
        val engine = syncedPaused(clock)
        assertEquals(
            listOf(SyncCommand.Play),
            engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 5), emptyList()).commands,
        )
        clock.advance(500)
        engine.onLocalSnapshot(clock.playing(10_000))

        // A write the server ordered before seq 5 arrives late: must not win.
        val stale = engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 10_000, seq = 4), emptyList())
        assertTrue(stale.commands.isEmpty())

        // A later write does win.
        val newer = engine.onRoom(clock.roomState(PlaybackStatus.PAUSED, 10_500, seq = 6), emptyList())
        assertEquals(listOf(SyncCommand.Pause), newer.commands)
    }

    @Test
    fun conflict_sameSeqRedelivered_isNotAppliedTwice() {
        val engine = syncedPaused(clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 2), emptyList())
        clock.advance(100)
        // Same write again (e.g. a members update re-delivers the room).
        val again = engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 10_000, seq = 2), emptyList())
        assertTrue(again.commands.isEmpty())
    }

    @Test
    fun appStartup_withinSettleWindow_isRealignedNotBroadcast() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(
            clock.roomState(PlaybackStatus.PAUSED, 60_000, seq = 3, updatedAt = clock.server - 10_000),
            emptyList(),
        )
        // Netflix opens in an unknown state, then autoplays from 0.
        engine.onLocalSnapshot(clock.snapshot(com.syncit.core.model.LocalPlaybackState.NONE, 0, 0))
        clock.advance(800)
        val out = engine.onLocalSnapshot(clock.playing(0))

        assertNull("autoplay is not a user action", out.broadcast)
        assertEquals(listOf(SyncCommand.Pause, SyncCommand.SeekTo(60_000)), out.commands)
    }

    @Test
    fun emptyRoom_isSeededOnceAppSettles() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(null, emptyList())
        engine.onLocalSnapshot(clock.playing(30_000))
        assertNull(engine.onTick().broadcast) // still settling

        clock.advance(4_001)
        val out = engine.onTick()
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 34_001, BroadcastReason.PLAY), out.broadcast)
    }
}
