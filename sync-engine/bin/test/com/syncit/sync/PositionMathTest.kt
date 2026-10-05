package com.syncit.sync

import com.syncit.core.model.Member
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionMathTest {

    private val clock = FakeClock()

    @Test
    fun live_playing_extrapolatesFromLastUpdate() {
        val s = clock.playing(10_000)
        assertEquals(13_000, PositionMath.localLivePositionMs(s, clock.elapsed + 3_000))
    }

    @Test
    fun live_playing_scalesBySpeed() {
        val s = clock.playing(10_000, speed = 2f)
        assertEquals(16_000, PositionMath.localLivePositionMs(s, clock.elapsed + 3_000))
    }

    @Test
    fun live_paused_doesNotMove() {
        val s = clock.paused(10_000)
        assertEquals(10_000, PositionMath.localLivePositionMs(s, clock.elapsed + 3_000))
    }

    @Test
    fun live_speedZeroWhilePlaying_treatedAsOneX() {
        val s = clock.playing(10_000, speed = 0f)
        assertEquals(12_000, PositionMath.localLivePositionMs(s, clock.elapsed + 2_000))
    }

    @Test
    fun live_unknownPosition_staysUnknown() {
        val s = clock.playing(PlaybackSnapshot.POSITION_UNKNOWN)
        assertEquals(PlaybackSnapshot.POSITION_UNKNOWN, PositionMath.localLivePositionMs(s, clock.elapsed + 2_000))
    }

    @Test
    fun roomExpected_playing_addsServerTimeSinceUpdate() {
        // Written 2.5 s ago by server clock: includes the network delay automatically.
        val state = clock.roomState(PlaybackStatus.PLAYING, 60_000, seq = 1, updatedAt = clock.server - 2_500)
        assertEquals(62_500, PositionMath.roomExpectedPositionMs(state, clock.server))
    }

    @Test
    fun roomExpected_paused_isStoredPosition() {
        val state = clock.roomState(PlaybackStatus.PAUSED, 60_000, seq = 1, updatedAt = clock.server - 2_500)
        assertEquals(60_000, PositionMath.roomExpectedPositionMs(state, clock.server))
    }

    @Test
    fun roomExpected_serverEstimateBehindUpdate_neverGoesBackwards() {
        val state = clock.roomState(PlaybackStatus.PLAYING, 60_000, seq = 1, updatedAt = clock.server + 200)
        assertEquals(60_000, PositionMath.roomExpectedPositionMs(state, clock.server))
    }

    @Test
    fun member_freshReport_extrapolatedWhilePlaying() {
        val m = Member("b", "B", online = true, positionMs = 30_000, reportedAt = clock.server - 4_000)
        assertEquals(34_000L, PositionMath.memberExpectedPositionMs(m, PlaybackStatus.PLAYING, clock.server, 15_000))
    }

    @Test
    fun member_staleReport_ignored() {
        val m = Member("b", "B", online = true, positionMs = 30_000, reportedAt = clock.server - 20_000)
        assertNull(PositionMath.memberExpectedPositionMs(m, PlaybackStatus.PLAYING, clock.server, 15_000))
    }
}
