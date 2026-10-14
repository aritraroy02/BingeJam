package com.syncit.media.activity

import com.syncit.core.model.ActivityApp
import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.social.ActivityUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDeciderTest {

    /** Fake SystemClock.elapsedRealtime(). */
    private var now = 1_000_000L
    private val decider = ActivityDecider()
    private val allApps = ActivityApp.entries.toSet()

    private fun session(
        app: ActivityApp,
        state: LocalPlaybackState,
        title: String? = "Title",
        key: String = app.packageName + "#1",
        position: Long = 0L,
        art: ByteArray? = null,
    ) = MediaSessionInfo(
        sessionKey = key,
        packageName = app.packageName,
        snapshot = PlaybackSnapshot(state, 0, position, now, 1f, emptySet(), 0L),
        metadata = NowPlayingMetadata(title, null, art),
    )

    private fun update(vararg sessions: MediaSessionInfo, shared: Set<ActivityApp> = allApps, room: String? = null) =
        decider.update(sessions.toList(), shared, room, now)

    /** Publishes whatever is due and returns it (wrapped so "publish null" is visible). */
    private fun publishDue(): ActivityDecider.Due? = decider.due(now)?.also { decider.onPublished(it.update) }

    private fun advance(ms: Long) {
        now += ms
    }

    /** Sets inputs, waits out the debounce and publishes. */
    private fun settle(vararg sessions: MediaSessionInfo, shared: Set<ActivityApp> = allApps, room: String? = null): ActivityUpdate? {
        update(*sessions, shared = shared, room = room)
        advance(ActivityDecider.DEBOUNCE_MS)
        update(*sessions, shared = shared, room = room)
        return publishDue()?.update
    }

    @Test fun playingBeatsPaused() {
        // Spotify must have played first, or its paused session doesn't count.
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING, "Song"))
        advance(1_000)
        update(
            session(ActivityApp.SPOTIFY, LocalPlaybackState.PAUSED, "Song"),
            session(ActivityApp.NETFLIX, LocalPlaybackState.PLAYING, "Movie"),
        )
        assertEquals(ActivityApp.NETFLIX, decider.desired?.app)
        assertTrue(decider.desired!!.playing)
    }

    @Test fun mostRecentlyChangedWins() {
        update(
            session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING, "Song"),
            session(ActivityApp.YOUTUBE, LocalPlaybackState.PAUSED, "Video"),
        )
        advance(5_000)
        // YouTube starts playing later: its change is more recent, though Spotify is first in order.
        update(
            session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING, "Song"),
            session(ActivityApp.YOUTUBE, LocalPlaybackState.PLAYING, "Video"),
        )
        assertEquals(ActivityApp.YOUTUBE, decider.desired?.app)
    }

    @Test fun unsharedAndUnknownAppsIgnored() {
        update(
            session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING),
            MediaSessionInfo("com.other#1", "com.other.player",
                PlaybackSnapshot(LocalPlaybackState.PLAYING, 0, 0, now, 1f, emptySet(), 0), null),
            shared = setOf(ActivityApp.NETFLIX),
        )
        assertNull(decider.desired)
    }

    @Test fun idleSessionWithMetadataIgnored() {
        // YouTube's muted home-feed preview: metadata but state NONE.
        update(session(ActivityApp.YOUTUBE, LocalPlaybackState.NONE, "Ad"))
        assertNull(decider.desired)
    }

    @Test fun pausedNeverSeenPlayingIgnored() {
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PAUSED, "Old song"))
        assertNull(decider.desired)
    }

    @Test fun debounceWaitsForStableValue() {
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING, "A"))
        advance(1_500)
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING, "B"))
        assertNull(decider.due(now))
        advance(1_500)
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING, "B"))
        assertNull("B has only been stable 1.5 s", decider.due(now))
        advance(500)
        assertEquals("B", publishDue()?.update?.metadata?.title)
    }

    @Test fun positionTicksDontRepublish() {
        assertNotNull(settle(session(ActivityApp.NETFLIX, LocalPlaybackState.PLAYING, position = 0)))
        for (i in 1..10) {
            advance(1_000)
            update(session(ActivityApp.NETFLIX, LocalPlaybackState.PLAYING, position = i * 1_000L))
            assertNull(decider.due(now))
        }
    }

    @Test fun pauseIsPublishedThenClearedAfterIdleTimeout() {
        settle(session(ActivityApp.NETFLIX, LocalPlaybackState.PLAYING))
        val paused = settle(session(ActivityApp.NETFLIX, LocalPlaybackState.PAUSED))
        assertFalse(paused!!.playing)

        advance(ActivityDecider.IDLE_TIMEOUT_MS - 10_000)
        update(session(ActivityApp.NETFLIX, LocalPlaybackState.PAUSED))
        assertNotNull(decider.desired)

        advance(10_000)
        update(session(ActivityApp.NETFLIX, LocalPlaybackState.PAUSED))
        assertNull(decider.desired)
        advance(ActivityDecider.DEBOUNCE_MS)
        val due = publishDue()
        assertNotNull(due)
        assertNull(due!!.update)
    }

    @Test fun sessionGoneKeepsLastActivityAsPausedUntilIdle() {
        settle(session(ActivityApp.NETFLIX, LocalPlaybackState.PLAYING, "Episode"))
        advance(1_000)
        update() // Netflix closed its session (left the player)
        assertEquals("Episode", decider.desired?.metadata?.title)
        assertFalse(decider.desired!!.playing)

        advance(ActivityDecider.IDLE_TIMEOUT_MS)
        update()
        assertNull(decider.desired)
    }

    @Test fun sharingOffClearsImmediately() {
        settle(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING))
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING), shared = emptySet())
        val due = publishDue()
        assertNotNull("no debounce when sharing is turned off", due)
        assertNull(due!!.update)
    }

    @Test fun roomCodeOnlyForJoinableAppsAndRepublishedOnChange() {
        val music = settle(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING), room = "ABC123")
        assertNull(music!!.roomCode)

        settle(session(ActivityApp.NETFLIX, LocalPlaybackState.PLAYING, "Movie"))
        val withRoom = settle(session(ActivityApp.NETFLIX, LocalPlaybackState.PLAYING, "Movie"), room = "ABC123")
        assertEquals("ABC123", withRoom!!.roomCode)
    }

    @Test fun primeVideoIsJoinable() {
        val update = settle(session(ActivityApp.PRIME_VIDEO, LocalPlaybackState.PLAYING, "Film"), room = "XYZ789")
        assertEquals(ActivityApp.PRIME_VIDEO, update!!.app)
        assertEquals("XYZ789", update.roomCode)
    }

    @Test fun artworkArrivingLaterRepublishes() {
        settle(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING, "Song"))
        val withArt = settle(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING, "Song", art = byteArrayOf(1, 2)))
        assertNotNull(withArt!!.metadata!!.artworkJpeg)
    }

    @Test fun firstDecisionIsPublishedEvenIfEmpty() {
        update()
        advance(ActivityDecider.DEBOUNCE_MS)
        val due = publishDue()
        assertNotNull("clears stale activity from a previous run", due)
        assertNull(due!!.update)
        assertNull(decider.due(now))
    }

    @Test fun failedPublishBacksOff() {
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING))
        advance(ActivityDecider.DEBOUNCE_MS)
        assertNotNull(decider.due(now))
        decider.onPublishFailed(now)
        advance(ActivityDecider.RETRY_MS - 1)
        assertNull(decider.due(now))
        advance(1)
        assertNotNull(decider.due(now))
    }

    @Test fun nextCheckCoversDebounceAndIdleDeadlines() {
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PLAYING))
        assertEquals(ActivityDecider.DEBOUNCE_MS, decider.nextCheckInMs(now))
        advance(ActivityDecider.DEBOUNCE_MS)
        publishDue()
        update(session(ActivityApp.SPOTIFY, LocalPlaybackState.PAUSED))
        advance(ActivityDecider.DEBOUNCE_MS)
        publishDue()
        // Paused 2 s ago: next check at most when it expires (capped at the max wait).
        assertTrue(decider.nextCheckInMs(now) <= ActivityDecider.IDLE_TIMEOUT_MS - ActivityDecider.DEBOUNCE_MS)
    }
}
