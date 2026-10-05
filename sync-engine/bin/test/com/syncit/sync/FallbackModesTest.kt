package com.syncit.sync

import com.syncit.core.model.Member
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.SyncCommand
import com.syncit.core.model.SyncMode
import com.syncit.core.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fallbacks chosen at runtime from supported actions and position behaviour. */
class FallbackModesTest {

    private val clock = FakeClock()

    @Test
    fun noSession_isManualCountdown() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onLocalSnapshot(null)
        assertEquals(SyncMode.MANUAL_COUNTDOWN, engine.mode)
        assertEquals(SyncStatus.APP_NOT_OPEN, engine.status)
    }

    @Test
    fun noPlayPauseActions_isManualCountdown_andNoCommands() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 60_000, seq = 1), emptyList())
        val out = engine.onLocalSnapshot(clock.paused(0, actions = emptySet()))
        assertEquals(SyncMode.MANUAL_COUNTDOWN, engine.mode)
        assertTrue(out.commands.isEmpty())
    }

    @Test
    fun noSeekTo_isCatchUpMode_andLateJoinOnlyMatchesPlayPause() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(
            clock.roomState(PlaybackStatus.PLAYING, 60_000, seq = 1, updatedAt = clock.server - 10_000),
            emptyList(),
        )
        val out = engine.onLocalSnapshot(clock.paused(0, actions = NO_SEEK_ACTIONS))
        assertEquals(SyncMode.PLAY_PAUSE_CATCH_UP, engine.mode)
        assertEquals(listOf(SyncCommand.Play), out.commands)
    }

    @Test
    fun catchUp_aheadOfRoom_pausesThenResumes() {
        val engine = syncedPlaying(clock, actions = NO_SEEK_ACTIONS)
        // 2.9 s ahead: past the drift threshold, below the seek threshold.
        engine.onLocalSnapshot(clock.playing(66_901, actions = NO_SEEK_ACTIONS))

        val pause = engine.onTick()
        assertEquals(listOf(SyncCommand.Pause), pause.commands)
        assertEquals(SyncStatus.CATCHING_UP, engine.status)

        // The resulting PAUSED callback is our own doing, so it's not broadcast.
        clock.advance(500)
        assertNull(engine.onLocalSnapshot(clock.paused(67_401, actions = NO_SEEK_ACTIONS)).broadcast)

        // After being paused as long as we were ahead, play again.
        clock.advance(2_400)
        assertEquals(listOf(SyncCommand.Play), engine.onTick().commands)
    }

    @Test
    fun catchUp_slowerMember_makesUsWait() {
        val engine = syncedPlaying(clock, actions = NO_SEEK_ACTIONS)
        // We're exactly on the room's position, but member B reported 4 s behind just now.
        val slow = Member("b", "B", online = true, positionMs = 60_001, reportedAt = clock.server)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 60_000, seq = 1, updatedAt = clock.server - 4_001), listOf(slow))

        assertEquals(listOf(SyncCommand.Pause), engine.onTick().commands)
    }

    @Test
    fun catchUp_behind_doesNothing() {
        val engine = syncedPlaying(clock, actions = NO_SEEK_ACTIONS)
        engine.onLocalSnapshot(clock.playing(62_001, actions = NO_SEEK_ACTIONS)) // 2 s behind
        assertTrue(engine.onTick().commands.isEmpty())
    }

    @Test
    fun positionUnknown_isPlayPauseOnly() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onLocalSnapshot(clock.playing(PlaybackSnapshot.POSITION_UNKNOWN))
        assertEquals(SyncMode.PLAY_PAUSE_ONLY, engine.mode)
    }

    @Test
    fun positionNeverUpdates_switchesToPlayPauseOnly_andStillSyncsPause() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 0, seq = 1), emptyList())
        engine.onLocalSnapshot(clock.playing(0))
        assertEquals(SyncMode.FULL, engine.mode)

        // Played 6 s, user pauses, and the app still says position 0.
        clock.advance(6_000)
        val out = engine.onLocalSnapshot(clock.paused(0))

        assertEquals(SyncMode.PLAY_PAUSE_ONLY, engine.mode)
        // Pause still goes to the room, keeping the room's own timeline position.
        assertEquals(PlaybackStatus.PAUSED, out.broadcast?.status)
        assertEquals(6_000L, out.broadcast?.positionMs)
    }

    @Test
    fun playPauseOnly_noDriftCorrectionOrReports() {
        val engine = DefaultSyncEngine(ME, clock)
        engine.onRoom(clock.roomState(PlaybackStatus.PLAYING, 0, seq = 1), emptyList())
        engine.onLocalSnapshot(clock.playing(PlaybackSnapshot.POSITION_UNKNOWN))
        clock.advance(10_000)
        val out = engine.onTick()
        assertTrue(out.commands.isEmpty())
        assertNull(out.positionReport)
    }
}
