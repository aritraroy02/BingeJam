package com.syncit.media.service

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpHoldTest {

    /** Fake SystemClock.elapsedRealtime(). */
    private var now = 1_000_000L
    private val hold = JumpHold(holdMs = 800, nearStartMs = 3_000, minJumpMs = 3_000)

    private fun reading(position: Long, title: String = "Song A", key: String = "spotify#1", updatedAt: Long = now) =
        MediaSessionInfo(
            sessionKey = key,
            packageName = "com.spotify.music",
            snapshot = PlaybackSnapshot(LocalPlaybackState.PLAYING, 3, position, updatedAt, 1f, emptySet(), 0L),
            metadata = NowPlayingMetadata(title, null, null),
        )

    @Test fun jumpToStartOfSameTrackIsHeldThenReleased() {
        val endOfTrack = reading(208_000)
        val atStart = reading(0)
        assertTrue(hold.shouldHold(endOfTrack, atStart, now))
        assertEquals(800L, hold.remainingMs(now))

        now += 799
        assertTrue(hold.shouldHold(endOfTrack, atStart, now))
        now += 1
        assertFalse("no metadata change: a real seek to the start", hold.shouldHold(endOfTrack, atStart, now))
        assertNull(hold.remainingMs(now))
    }

    @Test fun metadataChangeEndsTheHoldAtOnce() {
        val endOfTrack = reading(208_000)
        assertTrue(hold.shouldHold(endOfTrack, reading(0), now))
        now += 200
        assertFalse(hold.shouldHold(endOfTrack, reading(0, title = "Song B"), now))
    }

    @Test fun previousPositionIsExtrapolated() {
        // Last sample 10 s ago at 200 s, still playing: the app is at ~210 s now.
        val previous = reading(200_000, updatedAt = now - 10_000)
        assertTrue(hold.shouldHold(previous, reading(500), now))
    }

    @Test fun smallOrForwardJumpsAreNotHeld() {
        assertFalse("not near the start", hold.shouldHold(reading(100_000), reading(50_000), now))
        assertFalse("too small a jump", hold.shouldHold(reading(4_000), reading(1_500), now))
        assertFalse("forward", hold.shouldHold(reading(0), reading(60_000), now))
    }

    @Test fun differentSessionOrMissingReadingIsNotHeld() {
        assertFalse(hold.shouldHold(reading(200_000), reading(0, key = "spotify#2"), now))
        assertFalse(hold.shouldHold(null, reading(0), now))
        assertFalse(hold.shouldHold(reading(200_000), null, now))
    }

    @Test fun unknownPositionIsNotHeld() =
        assertFalse(hold.shouldHold(reading(200_000), reading(-1), now))
}
