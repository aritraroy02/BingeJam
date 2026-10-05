package com.syncit.sync

import com.syncit.core.model.BroadcastReason
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.StateBroadcast
import com.syncit.core.model.SyncCommand
import com.syncit.core.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3: seek detection, drift correction + rate limit, late join, position reports. */
class TimelineSyncTest {

    private val clock = FakeClock()

    // After syncedPlaying(), local and room are both at 64_001.

    @Test
    fun scrub_moreThanThreeSeconds_isBroadcastAsSeek() {
        val engine = syncedPlaying(clock)
        val out = engine.onLocalSnapshot(clock.playing(124_001))
        assertEquals(StateBroadcast(PlaybackStatus.PLAYING, 124_001, BroadcastReason.SEEK), out.broadcast)
    }

    @Test
    fun scrubBackwards_isBroadcastAsSeek() {
        val engine = syncedPlaying(clock)
        val out = engine.onLocalSnapshot(clock.playing(20_000))
        assertEquals(BroadcastReason.SEEK, out.broadcast?.reason)
        assertEquals(20_000L, out.broadcast?.positionMs)
    }

    @Test
    fun jitterUnderThreeSeconds_isNotASeek() {
        val engine = syncedPlaying(clock)
        val out = engine.onLocalSnapshot(clock.playing(66_001))
        assertNull(out.broadcast)
    }

    @Test
    fun remoteSeek_isApplied() {
        val engine = syncedPlaying(clock)
        val out = engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 600_000, seq = 2), emptyList())
        assertEquals(listOf(SyncCommand.SeekTo(600_000)), out.commands)
    }

    @Test
    fun echo_ourAppliedSeekIsNotRebroadcast() {
        val engine = syncedPlaying(clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 600_000, seq = 2), emptyList())
        clock.advance(700)
        val out = engine.onLocalSnapshot(clock.playing(600_100))
        assertNull(out.broadcast)
    }

    @Test
    fun drift_atOrBelowThreshold_isNotCorrected() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.playing(65_001)) // 1_000 ms ahead
        val out = engine.onTick()
        assertTrue(out.commands.isEmpty())
        assertEquals(1_000L, engine.lastDriftMs)
        assertEquals(SyncStatus.SYNCING, engine.status)
    }

    @Test
    fun drift_aboveThreshold_seeksToRoomExpectedPosition() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.playing(66_001)) // 2_000 ms ahead, not a seek
        val out = engine.onTick()
        assertEquals(listOf(SyncCommand.SeekTo(64_001)), out.commands)
        assertEquals(SyncStatus.CORRECTING, engine.status)
    }

    @Test
    fun drift_behind_alsoCorrected() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.playing(62_001)) // 2_000 ms behind
        assertEquals(listOf(SyncCommand.SeekTo(64_001)), engine.onTick().commands)
    }

    @Test
    fun drift_isOnlyCheckedEveryFiveSeconds() {
        val engine = syncedPlaying(clock)
        engine.onTick() // first check, in sync
        clock.advance(1_000)
        engine.onLocalSnapshot(clock.playing(67_001)) // 2_000 ahead of 65_001
        assertTrue(engine.onTick().commands.isEmpty())
        clock.advance(4_000)
        assertEquals(listOf(SyncCommand.SeekTo(69_001)), engine.onTick().commands)
    }

    @Test
    fun corrections_areRateLimited() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.playing(66_001))
        assertEquals(1, engine.onTick().commands.size) // correction #1 at t

        // App ignored the seek; still 2 s ahead at the next check (t + 5 s): cooldown, no seek.
        clock.advance(5_000)
        assertTrue(engine.onTick().commands.isEmpty())

        // t + 10 s: cooldown over, correct again.
        clock.advance(5_000)
        assertEquals(listOf(SyncCommand.SeekTo(74_001)), engine.onTick().commands)
    }

    @Test
    fun seeksThatNeverTakeEffect_turnTimelineSyncOff() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(clock.playing(66_001))
        repeat(3) {
            assertEquals(1, engine.onTick().commands.size)
            clock.advance(10_000)
        }
        // 4th time still off by 2 s: give up seeking.
        assertTrue(engine.onTick().commands.isEmpty())
        assertEquals(com.syncit.core.model.SyncMode.PLAY_PAUSE_ONLY, engine.mode)
    }

    @Test
    fun positionIsReportedPeriodically() {
        val engine = syncedPlaying(clock)
        assertEquals(64_001L, engine.onTick().positionReport)
        clock.advance(1_000)
        assertNull(engine.onTick().positionReport)
        clock.advance(4_000)
        assertEquals(69_001L, engine.onTick().positionReport)
    }

    @Test
    fun lateJoin_playingRoom_seeksToExpectedThenPlays() {
        val engine = DefaultSyncEngine(ME, clock)
        // Host pressed play at 60 s, 10 s ago (server time).
        engine.onRoom(
            clock.roomState(PlaybackStatus.PLAYING, 60_000, seq = 3, updatedAt = clock.server - 10_000),
            emptyList(),
        )
        val out = engine.onLocalSnapshot(clock.paused(0))
        assertEquals(listOf(SyncCommand.SeekTo(70_000), SyncCommand.Play), out.commands)
        assertNull(out.broadcast)
    }

    @Test
    fun lateJoin_pausedRoom_seeksToPausedPosition() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(
            clock.roomState(PlaybackStatus.PAUSED, 60_000, seq = 3, updatedAt = clock.server - 10_000),
            emptyList(),
        )
        val out = engine.onLocalSnapshot(clock.paused(0))
        assertEquals(listOf(SyncCommand.SeekTo(60_000)), out.commands)
    }

    @Test
    fun lateJoin_roomArrivesAfterApp_stillAligns() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onLocalSnapshot(clock.paused(0))
        val out = engine.onRoom(
            clock.roomState(PlaybackStatus.PLAYING, 60_000, seq = 3, updatedAt = clock.server - 10_000),
            emptyList(),
        )
        assertEquals(listOf(SyncCommand.SeekTo(70_000), SyncCommand.Play), out.commands)
    }

    @Test
    fun reopenApp_realignsToRoom() {
        val engine = syncedPlaying(clock)
        engine.onLocalSnapshot(null) // Netflix killed
        assertEquals(SyncStatus.SESSION_LOST, engine.status)

        clock.advance(20_000)
        // Reopened at its own resume point, paused. Room is at 64_001 + 20_000.
        val out = engine.onLocalSnapshot(clock.paused(30_000))
        assertEquals(listOf(SyncCommand.SeekTo(84_001), SyncCommand.Play), out.commands)
    }
}
