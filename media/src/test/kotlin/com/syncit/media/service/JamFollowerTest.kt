package com.syncit.media.service

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaAction
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StreamingService
import com.syncit.core.session.JamFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JamFollowerTest {

    /** Fake SystemClock.elapsedRealtime(). */
    private var now = 1_000_000L
    private val follower = JamFollower(startTimeoutMs = 6_000, leftGraceMs = 5_000)

    private val refA = "spotify:track:AAAAAAAAAAAAAAAAAAAAAA"
    private val refB = "spotify:track:BBBBBBBBBBBBBBBBBBBBBB"
    private val refC = "spotify:track:CCCCCCCCCCCCCCCCCCCCCC"
    private val bothPlayActions = setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.PLAY_FROM_URI, MediaAction.PLAY_FROM_SEARCH)

    private fun preview(
        title: String? = "Song A",
        ref: String? = refA,
        subtitle: String? = "Artist",
        service: StreamingService? = StreamingService.SPOTIFY,
    ) = RoomPreview("ABC123", "Host", service, title, subtitle, null, 0L, null, ref)

    private fun session(
        title: String? = "Song A",
        ref: String? = refA,
        actions: Set<MediaAction> = bothPlayActions,
        pkg: String = StreamingService.SPOTIFY.packageName,
    ) = MediaSessionInfo(
        sessionKey = "$pkg#1",
        packageName = pkg,
        snapshot = PlaybackSnapshot(LocalPlaybackState.PLAYING, 3, 0L, now, 1f, actions, 0L),
        metadata = if (title == null && ref == null) null else NowPlayingMetadata(title, null, null, playableRef = ref),
    )

    private fun update(p: RoomPreview?, s: MediaSessionInfo?) = follower.update(p, s, now)

    @Test fun onHostsTrackIsIdleAndVisible() {
        val d = update(preview(), session())
        assertEquals(JamFollow.IDLE, d.follow)
        assertFalse(d.hideSession)
        assertNull(d.action)
    }

    @Test fun noPreviewMeansNothingToFollow() {
        val d = update(null, session(title = "Other", ref = refC))
        assertEquals(JamFollow.IDLE, d.follow)
        assertFalse(d.hideSession)
    }

    @Test fun noSessionNeedsApp_thenFollowsWhenAppAppears() {
        val waiting = update(preview(), null)
        assertEquals(JamFollow.NEEDS_APP, waiting.follow)
        assertTrue(waiting.hideSession)

        now += 20_000 // waiting for the app doesn't use up the attempt or its timeout
        val d = update(preview(), session(title = "Old", ref = refC))
        assertEquals(JamFollow.STARTING, d.follow)
        assertEquals(JamFollower.Action.PlayFromUri("${StreamingService.SPOTIFY.packageName}#1", refA), d.action)
    }

    @Test fun sessionOfAnotherAppNeedsApp() {
        val d = update(preview(), session(pkg = StreamingService.YOUTUBE_MUSIC.packageName, ref = null))
        assertEquals(JamFollow.NEEDS_APP, d.follow)
    }

    @Test fun mismatchStartsHostTrackByUriOnce() {
        val first = update(preview(), session(title = "Song C", ref = refC))
        assertEquals(JamFollow.STARTING, first.follow)
        assertTrue(first.hideSession)
        assertTrue(first.action is JamFollower.Action.PlayFromUri)

        now += 2_000
        val second = update(preview(), session(title = "Song C", ref = refC))
        assertEquals(JamFollow.STARTING, second.follow)
        assertNull("only one automatic attempt per host track", second.action)

        now += 1_000
        val matched = update(preview(), session())
        assertEquals(JamFollow.IDLE, matched.follow)
        assertFalse(matched.hideSession)
    }

    @Test fun fallsBackToSearchWithoutUriSupportOrRef() {
        val noUri = update(preview(), session(title = "Old", ref = refC, actions = setOf(MediaAction.PLAY, MediaAction.PLAY_FROM_SEARCH)))
        assertEquals(JamFollower.Action.PlayFromSearch("${StreamingService.SPOTIFY.packageName}#1", "Song A Artist"), noUri.action)

        follower.reset()
        val noRef = update(preview(ref = null), session(title = "Old", ref = null))
        assertEquals(JamFollower.Action.PlayFromSearch("${StreamingService.SPOTIFY.packageName}#1", "Song A Artist"), noRef.action)
    }

    @Test fun hostRefInvalidForThisAppUsesSearch() {
        // A YouTube ref can never be played by Spotify; fall back to search.
        val p = preview(ref = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val d = update(p, session(title = "Old", ref = null))
        assertTrue(d.action is JamFollower.Action.PlayFromSearch)
    }

    @Test fun appThatCantBeToldWhatToPlayNeedsTheUiToOpenIt() {
        val noPlayFrom = setOf(MediaAction.PLAY, MediaAction.PAUSE)
        val d = update(preview(), session(title = "Old", ref = refC, actions = noPlayFrom))
        assertEquals("UI opens the app on the exact item", JamFollow.NEEDS_APP, d.follow)
        assertTrue(d.hideSession)
        assertFalse(d.otherTitle)
        assertNull(d.action)

        now += 10_000
        assertEquals(JamFollow.NEEDS_APP, update(preview(), session(title = "Old", ref = refC, actions = noPlayFrom)).follow)
        // "Back to the jam" can't do more than ask the UI again.
        follower.requestRetry()
        assertEquals(JamFollow.NEEDS_APP, update(preview(), session(title = "Old", ref = refC, actions = noPlayFrom)).follow)
        // The UI opened the track: following works.
        assertEquals(JamFollow.IDLE, update(preview(), session(actions = noPlayFrom)).follow)
    }

    @Test fun optedOutMemberIsNotPulledBackOnLaterHostTracks() {
        update(preview(), session())
        now += 1_000
        update(preview(), session(title = "Song C", ref = refC))
        now += 5_000
        assertEquals(JamFollow.FAILED, update(preview(), session(title = "Song C", ref = refC)).follow)

        // The host moves on: no automatic attempt for a member who chose something else.
        now += 60_000
        val next = update(preview(title = "Song B", ref = refB), session(title = "Song C", ref = refC))
        assertEquals(JamFollow.FAILED, next.follow)
        assertNull(next.action)

        // "Back to the jam" opts back in.
        follower.requestRetry()
        assertEquals(refB, (update(preview(title = "Song B", ref = refB), session(title = "Song C", ref = refC)).action as JamFollower.Action.PlayFromUri).uri)
    }

    @Test fun refArrivingAfterTitleIsTheSameTrack() {
        // Host's app published the title first (no ref yet): we start it by search.
        val first = update(preview(ref = null), session(title = "Old", ref = refC))
        assertTrue(first.action is JamFollower.Action.PlayFromSearch)
        // The ref arrives a moment later: same track, so no second attempt.
        now += 500
        val second = update(preview(), session(title = "Old", ref = refC))
        assertEquals(JamFollow.STARTING, second.follow)
        assertNull(second.action)
    }

    @Test fun timesOutToFailedAfterStartWindow() {
        update(preview(), session(title = "Old", ref = refC))
        now += 5_999
        assertEquals(JamFollow.STARTING, update(preview(), session(title = "Old", ref = refC)).follow)
        now += 1
        val d = update(preview(), session(title = "Old", ref = refC))
        assertEquals(JamFollow.FAILED, d.follow)
        assertTrue(d.hideSession)
        assertTrue(d.otherTitle)
        assertNull(d.action)
    }

    @Test fun actionNotAppliedFailsWithoutWaiting() {
        update(preview(), session(title = "Old", ref = refC))
        follower.actionNotApplied()
        assertEquals(JamFollow.FAILED, update(preview(), session(title = "Old", ref = refC)).follow)
    }

    @Test fun retryAfterFailureAttemptsAgain() {
        update(preview(), session(title = "Old", ref = refC))
        now += 7_000
        assertEquals(JamFollow.FAILED, update(preview(), session(title = "Old", ref = refC)).follow)

        follower.requestRetry()
        val d = update(preview(), session(title = "Old", ref = refC))
        assertEquals(JamFollow.STARTING, d.follow)
        assertTrue(d.action is JamFollower.Action.PlayFromUri)
    }

    @Test fun memberWhoLeavesHostTrackIsNotFought() {
        assertEquals(JamFollow.IDLE, update(preview(), session()).follow)

        // The member picks another song: wait for a possible host track change first...
        now += 1_000
        val grace = update(preview(), session(title = "Song C", ref = refC))
        assertEquals(JamFollow.IDLE, grace.follow)
        assertTrue("their other song mustn't move the room", grace.hideSession)
        assertNull(grace.action)

        // ...then give up without pulling them back.
        now += 5_000
        val d = update(preview(), session(title = "Song C", ref = refC))
        assertEquals(JamFollow.FAILED, d.follow)
        assertTrue(d.otherTitle)
        assertNull(d.action)

        // "Back to the jam" pulls them back.
        follower.requestRetry()
        assertTrue(update(preview(), session(title = "Song C", ref = refC)).action is JamFollower.Action.PlayFromUri)
    }

    @Test fun hostTrackChangeIsFollowedAutomatically() {
        update(preview(), session())
        now += 200_000
        // Member's app reached the end first and moved on to its own next song...
        update(preview(), session(title = "Song C", ref = refC))
        now += 500
        // ...then the host's next track arrives: start it.
        val d = update(preview(title = "Song B", ref = refB), session(title = "Song C", ref = refC))
        assertEquals(JamFollow.STARTING, d.follow)
        assertEquals(refB, (d.action as JamFollower.Action.PlayFromUri).uri)

        now += 1_500
        assertEquals(JamFollow.IDLE, update(preview(title = "Song B", ref = refB), session(title = "Song B", ref = refB)).follow)
    }

    @Test fun titleMatchUsedWhenEitherSideHasNoRef() {
        val d = update(preview(ref = null), session(title = "song   a", ref = refA))
        assertEquals(JamFollow.IDLE, d.follow)
        assertFalse(d.hideSession)
    }

    @Test fun differentRefsMismatchEvenWithSameTitle() {
        val d = update(preview(), session(title = "Song A", ref = refC))
        assertEquals(JamFollow.STARTING, d.follow)
    }

    @Test fun appWithoutTitleIsStartedThenTrusted() {
        val first = update(preview(), session(title = null, ref = null))
        assertEquals(JamFollow.STARTING, first.follow)
        assertTrue(first.hideSession)

        now += 6_000
        val later = update(preview(), session(title = null, ref = null))
        assertEquals("can't verify; don't block syncing", JamFollow.IDLE, later.follow)
        assertFalse(later.hideSession)
    }
}
