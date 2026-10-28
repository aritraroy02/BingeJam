package com.syncit.sync

import com.syncit.core.model.BroadcastReason
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.StateBroadcast
import com.syncit.core.model.SyncCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scrubs and stalls that pass through BUFFERING. Apps usually report a scrub as
 * PLAYING@a -> BUFFERING@b -> PLAYING@b, so the jump is only visible on the BUFFERING reading.
 */
class BufferingSeekTest {

    private val clock = FakeClock()

    // After syncedPlaying(), local and room are both at 64_001.

    @Test
    fun scrubThroughBuffering_whilePlaying_isBroadcastOnceSettled() {
        val engine = syncedPlaying(clock)
        assertNull("not settled yet", engine.onLocalSnapshot(clock.buffering(124_001)).broadcast)

        clock.advance(500)
        val out = engine.onLocalSnapshot(clock.playing(124_001))
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 124_001, BroadcastReason.SEEK), out.broadcast)
    }

    @Test
    fun scrubThroughBuffering_whilePaused_isBroadcast() {
        val engine = syncedPaused(clock)
        engine.onLocalSnapshot(clock.buffering(50_000))
        clock.advance(400)
        val out = engine.onLocalSnapshot(clock.paused(50_000))
        assertEquals(StateBroadcast(PlaybackStatus.PAUSED, 50_000, BroadcastReason.SEEK), out.broadcast)
    }

    @Test
    fun scrubThroughBuffering_isNotPulledBackByDriftCheck() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.buffering(124_001))

        // Still buffering at the next drift check: the old room position must not win.
        clock.advance(5_000)
        assertTrue(engine.onTick().commands.isEmpty())

        val out = engine.onLocalSnapshot(clock.playing(124_001))
        assertEquals(124_001L, out.broadcast?.positionMs)
        // Our write comes back; afterwards drift checks find nothing to fix.
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 124_001, seq = 2, by = ME), emptyList())
        clock.advance(5_000)
        engine.onLocalSnapshot(clock.playing(129_001))
        assertTrue(engine.onTick().commands.isEmpty())
    }

    @Test
    fun scrubThroughBuffering_thatLandsOnTheRoomPosition_isNotBroadcast() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.buffering(124_001))
        clock.advance(300)
        // The user scrubbed away and straight back before the app settled.
        assertNull(engine.onLocalSnapshot(clock.playing(64_301)).broadcast)
    }

    @Test
    fun networkStall_isNotASeek() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.buffering(64_001))
        // Buffering doesn't advance the position, so 5 s later the app resumes where it stalled.
        clock.advance(5_000)
        assertTrue("no drift correction while buffering", engine.onTick().commands.isEmpty())
        val out = engine.onLocalSnapshot(clock.playing(64_001))
        assertNull(out.broadcast)
    }

    @Test
    fun scrubThenLongBuffering_isBroadcastWhenHoldExpires() {
        val config = SyncConfig()
        val engine = syncedPlaying(clock, config = config)
        engine.onLocalSnapshot(clock.buffering(124_001))

        clock.advance(config.transientHoldMs - 1)
        assertNull(engine.onTick().broadcast)
        clock.advance(1)
        // Keep the room's play state; the position is where the app is stuck buffering.
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 124_001, BroadcastReason.SEEK), engine.onTick().broadcast)

        // Settling at the same spot afterwards isn't broadcast again.
        clock.advance(500)
        assertNull(engine.onLocalSnapshot(clock.playing(124_001)).broadcast)
    }

    @Test
    fun echo_ourSeekThroughBuffering_isNotBroadcast() {
        val engine = syncedPlaying(clock)
        assertEquals(
            listOf(SyncCommand.SeekTo(600_000)),
            engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 600_000, seq = 2), emptyList()).commands,
        )
        clock.advance(200)
        assertNull(engine.onLocalSnapshot(clock.buffering(600_000)).broadcast)
        // Buffering outlasts the 1.5 s echo window; the expectation is held until the app settles.
        clock.advance(2_300)
        assertNull(engine.onLocalSnapshot(clock.playing(600_000)).broadcast)
    }

    @Test
    fun echo_ourSeek_appBuffersAtOldPositionFirst_isNotBroadcast() {
        val engine = syncedPlaying(clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 600_000, seq = 2), emptyList())
        clock.advance(200)
        // Some apps buffer at the old position and only report the new one when they resume.
        engine.onLocalSnapshot(clock.buffering(64_201))
        clock.advance(2_300)
        assertNull(engine.onLocalSnapshot(clock.playing(600_000)).broadcast)
    }

    @Test
    fun echo_heldExpectationExpires_afterSettling() {
        val engine = syncedPlaying(clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 600_000, seq = 2), emptyList())
        clock.advance(200)
        engine.onLocalSnapshot(clock.buffering(600_000))
        clock.advance(2_300)
        engine.onLocalSnapshot(clock.playing(600_000))

        // 3 s after settling the user scrubs near the old target: that's a real seek now.
        clock.advance(3_000)
        val out = engine.onLocalSnapshot(clock.playing(500_000))
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 500_000, BroadcastReason.SEEK), out.broadcast)
    }

    @Test
    fun remoteWriteWhileBufferingAfterScrub_roomWins() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.buffering(124_001))
        clock.advance(300)
        // Someone else's later write arrives while we buffer: the room is authoritative.
        val applied = engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 300_000, seq = 2), emptyList())
        assertEquals(listOf(SyncCommand.SeekTo(300_000)), applied.commands)

        clock.advance(700)
        assertNull(engine.onLocalSnapshot(clock.playing(300_000)).broadcast)
    }

    @Test
    fun jumpThroughBuffering_duringAttachSettle_isRealignedNotBroadcast() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 60_000, seq = 1), emptyList())
        engine.onLocalSnapshot(clock.playing(60_000))

        // The app jumps to its own resume point while starting up, via buffering...
        clock.advance(1_000)
        engine.onLocalSnapshot(clock.buffering(200_000))
        // ...and only settles after the 4 s settle window.
        clock.advance(4_000)
        val out = engine.onLocalSnapshot(clock.playing(200_000))
        assertNull(out.broadcast)
        assertEquals(listOf(SyncCommand.SeekTo(65_000)), out.commands)
    }

    // ---- Stalls that are reported late (the app kept saying PLAYING while frozen) ----
    // syncedPlaying()'s last raw reading is PLAYING@60_000, sampled 4_001 ms ago, so it
    // extrapolates to 64_001. The video actually froze at ~60_500.

    @Test
    fun frozenWhileReportingPlaying_thenBufferingAtOlderPosition_isAStallNotASeek() {
        val engine = syncedPlaying(clock)
        // 3.5 s behind the extrapolation, but after the last raw position: a stall.
        assertNull(engine.onLocalSnapshot(clock.buffering(60_500)).broadcast)
        clock.advance(1_000)
        val out = engine.onLocalSnapshot(clock.playing(60_500))
        assertNull("a stall must not seek the whole room backwards", out.broadcast)

        // The drift check corrects the stalled phone forward instead (room: 64_001 + 1_000).
        assertEquals(listOf(SyncCommand.SeekTo(65_001)), engine.onTick().commands)
    }

    @Test
    fun stallReportedSlightlyBeforeLastRawPosition_isAStall() {
        val engine = syncedPlaying(clock)
        // Apps round/sample positions; a little before the last raw reading is still a stall.
        engine.onLocalSnapshot(clock.buffering(58_000))
        clock.advance(500)
        assertNull(engine.onLocalSnapshot(clock.playing(58_000)).broadcast)
    }

    @Test
    fun scrubBackBeforeLastRawPosition_throughBuffering_isASeek() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.buffering(20_000))
        clock.advance(500)
        assertEquals(
            StateBroadcast(PlaybackStatus.PLAYING, 20_000, BroadcastReason.SEEK),
            engine.onLocalSnapshot(clock.playing(20_000)).broadcast,
        )
    }

    @Test
    fun scrubJustPastExtrapolationPlusThreshold_throughBuffering_isASeek() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.buffering(67_002)) // 64_001 + 3_000 + 1
        clock.advance(300)
        assertEquals(BroadcastReason.SEEK, engine.onLocalSnapshot(clock.playing(67_002)).broadcast?.reason)
    }

    @Test
    fun tenSecondFreeze_isStillAStall() {
        val engine = syncedPlaying(clock)
        // Frozen at 60_000 while still "PLAYING"; 10 s after the raw report it extrapolates to 70_000.
        clock.advance(10_000 - 4_001)
        engine.onLocalSnapshot(clock.buffering(60_000))
        clock.advance(500)
        assertNull(engine.onLocalSnapshot(clock.playing(60_000)).broadcast)
    }

    @Test
    fun oldRawReport_scrubBackWithinItsRange_isASeek() {
        // Last raw report PLAYING@10:00, 20 minutes ago: extrapolates to 30:00.
        val engine = syncedPlaying(clock, positionMs = 600_000)
        clock.advance(1_200_000 - 4_001)
        // Scrub back to 25:00: after the raw 10:00, but 5 min behind the extrapolation, far past
        // stallWindowMaxMs, so not a stall.
        engine.onLocalSnapshot(clock.buffering(1_500_000))
        clock.advance(500)
        assertEquals(
            StateBroadcast(PlaybackStatus.PLAYING, 1_500_000, BroadcastReason.SEEK),
            engine.onLocalSnapshot(clock.playing(1_500_000)).broadcast,
        )
    }
}
